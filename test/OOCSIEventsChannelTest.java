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

public class OOCSIEventsChannelTest {

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
	public void testEventsOnChannelMessage() throws Exception {
		assertNotNull("OOCSIServer instance should be available", OOCSIServer.getInstance());

		final List<Map<String, Object>> events = Collections.synchronizedList(new ArrayList<>());

		OOCSIClient listener = new OOCSIClient("events_listener");
		listener.connect("localhost", 4444);
		assertTrue(listener.isConnected());

		listener.subscribe("OOCSI_events", new DataHandler() {
			public void receive(String sender, Map<String, Object> data, long timestamp) {
				events.add(data);
			}
		});

		Thread.sleep(300);

		OOCSIClient receiver = new OOCSIClient("receiver_client");
		receiver.connect("localhost", 4444);
		assertTrue(receiver.isConnected());
		receiver.subscribe("test_channel", new DataHandler() {
			public void receive(String sender, Map<String, Object> data, long timestamp) {
			}
		});

		Thread.sleep(300);

		OOCSIClient sender = new OOCSIClient("sender_client");
		sender.connect("localhost", 4444);
		assertTrue(sender.isConnected());

		new OOCSIMessage(sender, "test_channel").data("msg", "hello").send();

		Thread.sleep(800);

		assertEquals("Expected to receive event in OOCSI_events", 1, events.size());
		Map<String, Object> event = events.get(0);
		assertEquals("sender_client", event.get("PUB"));
		assertEquals("test_channel", event.get("CHANNEL"));

		listener.disconnect();
		receiver.disconnect();
		sender.disconnect();
	}
}
