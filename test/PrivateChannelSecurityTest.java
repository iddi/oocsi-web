import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import nl.tue.id.oocsi.client.OOCSIClient;
import nl.tue.id.oocsi.client.protocol.DataHandler;
import nl.tue.id.oocsi.client.protocol.OOCSIMessage;
import nl.tue.id.oocsi.server.OOCSIServer;

public class PrivateChannelSecurityTest {

	private OOCSIServer server;

	@Before
	public void setUp() throws Exception {
		OOCSIServer existing = OOCSIServer.getInstance();
		if (existing != null) {
			existing.stop();
		}
		server = new OOCSIServer(4444, 1000, true);
	}

	@After
	public void tearDown() {
		if (server != null) {
			server.stop();
			server = null;
		}
	}

	@Test
	public void testPrivateChannelSecretNotDisclosedOnEventsChannel() throws Exception {
		assertNotNull("OOCSIServer instance should be available", OOCSIServer.getInstance());

		final List<Map<String, Object>> eventList = Collections.synchronizedList(new ArrayList<>());
		final List<Map<String, Object>> privateReceived = Collections.synchronizedList(new ArrayList<>());

		// Spy listens to OOCSI_events
		OOCSIClient spy = new OOCSIClient("spy_client");
		spy.connect("localhost", 4444);
		assertTrue(spy.isConnected());

		spy.subscribe("OOCSI_events", new DataHandler() {
			public void receive(String sender, Map<String, Object> data, long timestamp) {
				eventList.add(data);
			}
		});

		Thread.sleep(300);

		// Bob subscribes to private channel with secret
		OOCSIClient bob = new OOCSIClient("bob_client");
		bob.connect("localhost", 4444);
		assertTrue(bob.isConnected());

		bob.subscribe("confidential_room:superSecretPass123", new DataHandler() {
			public void receive(String sender, Map<String, Object> data, long timestamp) {
				privateReceived.add(data);
			}
		});

		Thread.sleep(300);

		// Alice sends to private channel with secret
		OOCSIClient alice = new OOCSIClient("alice_client");
		alice.connect("localhost", 4444);
		assertTrue(alice.isConnected());

		new OOCSIMessage(alice, "confidential_room:superSecretPass123").data("secret_msg", "classified").send();

		Thread.sleep(800);

		// Bob must receive the private message
		assertEquals("Bob should have received 1 private message", 1, privateReceived.size());
		assertEquals("classified", privateReceived.get(0).get("secret_msg"));

		// Spy must have received ZERO events regarding the private channel
		for (Map<String, Object> event : eventList) {
			String chan = String.valueOf(event.get("CHANNEL"));
			assertTrue("Event channel must not contain the secret", !chan.contains("superSecretPass123"));
			assertTrue("Event channel must not reference confidential_room", !chan.contains("confidential_room"));
		}
		assertEquals("Spy must receive zero events for private channel communication", 0, eventList.size());

		// Now send a message to a public channel to confirm OOCSI_events still works normally
		OOCSIClient charlie = new OOCSIClient("charlie_client");
		charlie.connect("localhost", 4444);
		assertTrue(charlie.isConnected());
		charlie.subscribe("public_room", new DataHandler() {
			public void receive(String sender, Map<String, Object> data, long timestamp) {
			}
		});
		Thread.sleep(300);

		new OOCSIMessage(alice, "public_room").data("public_msg", "hello_world").send();
		Thread.sleep(800);

		assertEquals("Spy must receive event for public channel message", 1, eventList.size());
		Map<String, Object> pubEvent = eventList.get(0);
		assertEquals("public_room", pubEvent.get("CHANNEL"));
		assertEquals("alice_client", pubEvent.get("PUB"));

		spy.disconnect();
		bob.disconnect();
		alice.disconnect();
		charlie.disconnect();
	}

	@Test
	public void testPrivateChannelNotDisclosedOnConnectionsChannel() throws Exception {
		final List<Map<String, Object>> connectionsList = Collections.synchronizedList(new ArrayList<>());

		OOCSIClient spy = new OOCSIClient("connections_spy");
		spy.connect("localhost", 4444);
		assertTrue(spy.isConnected());

		spy.subscribe("OOCSI_connections", new DataHandler() {
			public void receive(String sender, Map<String, Object> data, long timestamp) {
				connectionsList.add(data);
			}
		});

		Thread.sleep(300);

		// Bob subscribes to private channel
		OOCSIClient bob = new OOCSIClient("bob_private_subscriber");
		bob.connect("localhost", 4444);
		assertTrue(bob.isConnected());

		bob.subscribe("secret_vault:secretVaultToken999", new DataHandler() {
			public void receive(String sender, Map<String, Object> data, long timestamp) {
			}
		});

		Thread.sleep(500);

		// Verify spy received no secret in connection events
		for (Map<String, Object> connEvent : connectionsList) {
			String chan = String.valueOf(connEvent.get("CHANNEL"));
			assertTrue("Connection channel must not contain secret token", !chan.contains("secretVaultToken999"));
			assertTrue("Connection channel must not contain secret vault name", !chan.contains("secret_vault"));
		}

		bob.disconnect();
		spy.disconnect();
	}
}
