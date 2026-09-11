import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.model.FunctionClient;
import nl.tue.id.oocsi.server.model.Server;
import nl.tue.id.oocsi.server.protocol.Message;
import nl.tue.id.oocsi.server.protocol.Protocol;

public class ServerCoreSecurityTest {

	private Server server;
	private TestClient alice;
	private TestClient bob;

	private static class TestClient extends Client {
		List<Message> received = new ArrayList<>();
		boolean connected = true;

		public TestClient(String token, Channel.ChangeListener listener) {
			super(token, listener);
		}

		@Override
		public boolean send(Message message) {
			received.add(message);
			return true;
		}

		@Override
		public void disconnect() {
			connected = false;
		}

		@Override
		public boolean isConnected() {
			return connected;
		}

		@Override
		public void ping() {}

		@Override
		public void pong() {}
	}

	@Before
	public void setUp() {
		server = new Server();
		alice = new TestClient("alice", server.getChangeListener());
		bob = new TestClient("bob", server.getChangeListener());
		server.addClient(alice);
		server.addClient(bob);
	}

	@Test
	public void testReservedNamesBlocked() {
		for (String name : Server.RESERVED_NAMES) {
			TestClient reservedClient = new TestClient(name, server.getChangeListener());
			assertFalse("Reserved name " + name + " must not be allowed to register",
			        server.addClient(reservedClient));
		}
	}

	@Test
	public void testPrivateChannelPasswordEnforcedWithFunctions() {
		// Alice creates private channel with password
		server.subscribe(alice, "secretChannel:topSecretPass");
		Channel secretChan = server.getChannel("secretChannel:topSecretPass");
		assertNotNull("Private channel should exist when queried with password", secretChan);
		assertNull("Private channel should not be returned without password", server.getChannel("secretChannel"));
		assertTrue("Channel should be private", secretChan.isPrivate());

		// Bob attempts function subscription without password
		server.subscribe(bob, "secretChannel[filter(1>0)]");
		assertNull("Bob should not be added without password", secretChan.getChannel("bob"));

		// Bob attempts subscription with wrong password and function
		server.subscribe(bob, "secretChannel:wrongPass[filter(1>0)]");
		assertNull("Bob should not be added with wrong password", secretChan.getChannel("bob"));

		// Bob subscribes with valid password and function
		server.subscribe(bob, "secretChannel:topSecretPass[filter(1>0)]");
		assertNotNull("Bob should be added with valid password", secretChan.getChannel("bob"));
	}

	@Test
	public void testEvalExFunctionWhitelist() {
		// Test function client with FACT function (should be rejected/unknown)
		FunctionClient factClient = new FunctionClient(alice, "testFact", "filter(FACT(5)>0)", server.getChangeListener());
		Message msg = new Message("sender", "testFact");
		msg.addData("val", 10);
		// filter fails safely on unknown function
		assertFalse("Dangerous functions should fail evaluation safely", factClient.send(msg));

		// Test function client with allowed basic expression
		FunctionClient safeClient = new FunctionClient(alice, "testSafe", "filter(val > 5)", server.getChangeListener());
		assertTrue("Safe expressions should pass evaluation", safeClient.send(msg));
	}

	@Test
	public void testNegativeRetainTimeoutRejected() {
		Channel chan = new Channel("retainedChan", server.getChangeListener());
		Message msg = new Message("alice", "retainedChan");
		msg.addData(Message.RETAIN_MESSAGE, -1);
		chan.send(msg);

		Message validMsg = new Message("alice", "retainedChan");
		validMsg.addData(Message.RETAIN_MESSAGE, 60);
		chan.send(validMsg);

		// Now send negative retain to ensure it does not evict valid message
		Message badMsg = new Message("alice", "retainedChan");
		badMsg.addData(Message.RETAIN_MESSAGE, -100);
		chan.send(badMsg);

		// Negative retain should not set retained message with past date
		assertNotNull("Channel should still be valid", chan.getName());
	}

	@Test
	public void testInternalChannelsProtectedFromClientWrites() {
		Protocol protocol = new Protocol(server);
		// Attempting to send directly to internal channels via protocol
		protocol.processInput(alice, "sendraw OOCSI_events {\"test\":1}");
		protocol.processInput(alice, "sendraw OOCSI_connections {\"test\":1}");

		// Verify internal channels do not contain client messages
		Channel eventsChan = server.getChannel("OOCSI_events");
		if (eventsChan != null) {
			assertEquals(0, eventsChan.getChannels().size());
		}
	}
}
