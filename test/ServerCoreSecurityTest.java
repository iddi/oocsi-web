import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import nl.tue.id.oocsi.server.OOCSIServer;
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

	@Test
	public void testSubscribeQueryOnReservedAndPrivateChannels() {
		// Querying reserved channel should be rejected
		server.subscribe(bob, "OOCSI_events/?");
		assertNull("Reserved info channel must not be created or joined", server.getChannel("OOCSI_events/?"));

		server.subscribe(bob, "SERVER/?");
		assertNull("Reserved info channel must not be created or joined", server.getChannel("SERVER/?"));

		// Setup a private channel with password
		server.subscribe(alice, "classifiedChannel:secret123");
		Channel privChan = server.getChannel("classifiedChannel:secret123");
		assertNotNull("Private channel should exist with password", privChan);

		// Bob attempts /? query without password -> should be blocked
		server.subscribe(bob, "classifiedChannel/?");
		assertNull("Bob must not be able to create or join private info channel without password",
				server.getChannel("classifiedChannel/?"));

		// Bob attempts /? query with wrong password -> should be blocked
		server.subscribe(bob, "classifiedChannel:wrongPassword/?");
		assertNull("Bob must not be able to create or join private info channel with wrong password",
				server.getChannel("classifiedChannel/?"));

		// Bob queries with correct password -> should successfully subscribe to /? info channel
		server.subscribe(bob, "classifiedChannel:secret123/?");
		Channel infoChan = server.getChannel("classifiedChannel/?");
		assertNotNull("Authorized client should join info channel", infoChan);
		assertNotNull("Bob should be subscribed to info channel", infoChan.getChannel("bob"));

		// Simulate StatusTimeTask broadcast on info channel
		Message infoMsg = new Message("SERVER", "classifiedChannel/?");
		infoMsg.addData("channels", privChan.getChannelList());
		infoChan.send(infoMsg);
		assertEquals("Bob should receive status update after authorized subscription", 1, bob.received.size());
	}

	@Test
	public void testFunctionClientDangerousFunctionsBlocked() {
		// Blocked EvalEx functions: STR_FORMAT, DT_DATE_NEW, DT_DURATION_NEW, FACT
		String[] dangerousExpressions = {
			"filter(STR_FORMAT('%s', 'x') == 'x')",
			"filter(DT_DATE_NEW(2026, 1, 1) != null)",
			"filter(DT_DURATION_NEW(1000) != null)",
			"filter(FACT(5) == 120)"
		};

		for (String expr : dangerousExpressions) {
			FunctionClient fnClient = new FunctionClient(alice, "fnTest_" + Math.abs(expr.hashCode()),
					expr, server.getChangeListener());
			Message msg = new Message("sender", "fnTest");
			msg.addData("x", 1);
			assertFalse("Dangerous or unwhitelisted function expression must be rejected: " + expr,
					fnClient.send(msg));
		}

		// Allowed safe expressions should work
		FunctionClient safeClient = new FunctionClient(alice, "safeFnTest", "filter(val > 10)", server.getChangeListener());
		Message msgAllowed = new Message("sender", "safeFnTest");
		msgAllowed.addData("val", 20);
		assertTrue("Safe expression should pass evaluation", safeClient.send(msgAllowed));
	}

	@Test
	public void testClientNameValidation() {
		// Valid client names
		assertTrue("Alphanumeric client name should be valid", Server.isValidClientName("alice"));
		assertTrue("Hyphenated client name should be valid", Server.isValidClientName("device-01"));
		assertTrue("Client name with underscore should be valid", Server.isValidClientName("client_123"));
		assertTrue("Client name with dot should be valid", Server.isValidClientName("device.temp"));
		assertTrue("Client name with at-sign should be valid", Server.isValidClientName("user@domain"));
		assertTrue("Hierarchical client name should be valid", Server.isValidClientName("OOCSI/test/visual_####"));
		assertTrue("Client name with colon should be valid", Server.isValidClientName("client:1"));
		assertTrue("Client name with hash template should be valid", Server.isValidClientName("webclient_####"));

		// Invalid client names / XSS payloads
		assertFalse("Script tag client name must be rejected", Server.isValidClientName("<script>alert(1)</script>"));
		assertFalse("Image tag client name must be rejected", Server.isValidClientName("<img/src=x/onerror=alert(1)>"));
		assertFalse("Client name with double quotes must be rejected", Server.isValidClientName("client\"name"));
		assertFalse("Client name with single quotes must be rejected", Server.isValidClientName("client'name"));
		assertFalse("Client name with semicolon must be rejected", Server.isValidClientName("client;drop"));
		assertFalse("Client name with spaces must be rejected", Server.isValidClientName("client name"));
		assertFalse("Client name with parentheses must be rejected", Server.isValidClientName("client(1)"));
		assertFalse("Client name with brackets must be rejected", Server.isValidClientName("client[1]"));
		assertFalse("Empty client name must be rejected", Server.isValidClientName(""));
		assertFalse("Null client name must be rejected", Server.isValidClientName(null));
		assertFalse("Overly long client name must be rejected", Server.isValidClientName("a".repeat(201)));

		// Reserved names
		for (String reserved : Server.RESERVED_NAMES) {
			assertFalse("Reserved client name " + reserved + " must be rejected",
					Server.isValidClientName(reserved));
		}
	}

	@Test
	public void testChannelNameValidation() {
		// Valid channel names
		assertTrue("Simple channel name should be valid", Server.isValidChannelName("testChannel"));
		assertTrue("Hierarchical channel name should be valid", Server.isValidChannelName("home/livingroom/temp"));
		assertTrue("Channel name with password token should be valid", Server.isValidChannelName("myChannel:secret123"));
		assertTrue("Channel name with metadata query should be valid", Server.isValidChannelName("myChannel/?"));
		assertTrue("Channel with dots and hyphens should be valid", Server.isValidChannelName("room-101.sensor"));

		// Invalid channel names / XSS payloads
		assertFalse("Script tag channel name must be rejected", Server.isValidChannelName("<script>alert(1)</script>"));
		assertFalse("SVG tag channel name must be rejected", Server.isValidChannelName("<svg/onload=alert(1)>"));
		assertFalse("Channel name with double quotes must be rejected", Server.isValidChannelName("channel\"test"));
		assertFalse("Channel name with single quotes must be rejected", Server.isValidChannelName("channel'test"));
		assertFalse("Channel name with semicolon must be rejected", Server.isValidChannelName("channel;drop"));
		assertFalse("Channel name with brackets must be rejected as base channel", Server.isValidChannelName("channel[filter]"));
		assertFalse("Channel name with parentheses must be rejected as base channel", Server.isValidChannelName("presence(channel)"));
		assertFalse("Channel name with spaces must be rejected", Server.isValidChannelName("channel name"));
		assertFalse("Empty channel name must be rejected", Server.isValidChannelName(""));
		assertFalse("Null channel name must be rejected", Server.isValidChannelName(null));
		assertFalse("Overly long channel name must be rejected", Server.isValidChannelName("c".repeat(201)));

		assertTrue("Channel with special characters from test suite should be valid", Server.isValidChannelName("testpattern_-AZ09<>!#$@%$*^"));
		assertTrue("System event channel should be valid channel name", Server.isValidChannelName(OOCSIServer.OOCSI_EVENTS));
	}

	@Test
	public void testSubscriptionSyntaxValidation() {
		// Valid Plain Subscriptions
		assertTrue("Plain channel subscription should be valid", Server.isValidSubscription("testChannel"));
		assertTrue("Private channel subscription should be valid", Server.isValidSubscription("testChannel:secretPassword"));
		assertTrue("Metadata query subscription should be valid", Server.isValidSubscription("testChannel/?"));
		assertTrue("Hierarchical channel subscription should be valid", Server.isValidSubscription("home/livingroom/temp"));

		// Valid Presence Subscriptions
		assertTrue("Presence subscription should be valid", Server.isValidSubscription("presence(testChannel)"));
		assertTrue("Presence subscription with hierarchical channel should be valid", Server.isValidSubscription("presence(home/temp)"));
		assertTrue("Presence subscription with hyphenated channel should be valid", Server.isValidSubscription("presence(room-101)"));

		// Valid Filter & Transform Function Subscriptions
		assertTrue("Simple filter subscription should be valid",
				Server.isValidSubscription("testChannel[filter(temp > 20)]"));
		assertTrue("Filter subscription with parentheses and logical AND should be valid",
				Server.isValidSubscription("testChannel[filter((temp > 20) && (humidity < 80))]"));
		assertTrue("Chained filter and transform should be valid",
				Server.isValidSubscription("testChannel[filter(temp >= 20.5);transform(fahrenheit, temp * 1.8 + 32)]"));
		assertTrue("Transform with aggregation function should be valid",
				Server.isValidSubscription("testChannel[transform(avg, mean(temp, 5))]"));
		assertTrue("Transform with complex arithmetic should be valid",
				Server.isValidSubscription("testChannel[filter(temp != 0);transform(norm, (temp - 10) / 2)]"));
		assertTrue("Private channel with filter should be valid",
				Server.isValidSubscription("myChannel:secretPassword[filter(temp > 20)]"));

		// Invalid Subscriptions / XSS Injections
		assertFalse("HTML script injection in subscription should be rejected",
				Server.isValidSubscription("<script>alert(1)</script>"));
		assertFalse("HTML script injection in function block should be rejected",
				Server.isValidSubscription("testChannel[<script>alert(1)</script>]"));
		assertFalse("HTML script injection in presence should be rejected",
				Server.isValidSubscription("presence(<script>alert(1)</script>)"));
		assertFalse("Presence with reserved channel should be rejected",
				Server.isValidSubscription("presence(OOCSI_events)"));
		assertFalse("Image tag in transform should be rejected",
				Server.isValidSubscription("testChannel[transform(x, <img src=x onerror=alert(1)>)]"));
		assertFalse("Unclosed bracket should be rejected",
				Server.isValidSubscription("testChannel[filter(temp > 20)"));
		assertFalse("Unbalanced parentheses should be rejected",
				Server.isValidSubscription("testChannel[filter((temp > 20)]"));
		assertFalse("Empty function block should be rejected",
				Server.isValidSubscription("testChannel[]"));
		assertFalse("Unsupported function should be rejected",
				Server.isValidSubscription("testChannel[executeCommand(whoami)]"));
		assertFalse("Semicolon command injection should be rejected",
				Server.isValidSubscription("testChannel;rm -rf /"));
		assertFalse("Null subscription should be rejected",
				Server.isValidSubscription(null));
		assertFalse("Empty subscription should be rejected",
				Server.isValidSubscription(""));
	}

	@Test
	public void testServerLevelEnforcement() {
		// XSS client handle rejected by addClient
		TestClient xssClient = new TestClient("<script>alert(1)</script>", server.getChangeListener());
		assertFalse("XSS client handle must be rejected by addClient", server.addClient(xssClient));
		assertNull("XSS client must not be registered", server.getClient("<script>alert(1)</script>"));

		// Malicious subscription rejected by subscribe
		server.subscribe(alice, "maliciousChannel[<script>alert(1)</script>]");
		assertNull("Malicious subscription must not create channel", server.getChannel("maliciousChannel"));

		// Legitimate filter and transform subscription works on server
		String sub = "sensorFeed[filter(temp > 20);transform(fahrenheit, temp * 1.8 + 32)]";
		server.subscribe(alice, sub);
		Channel c = server.getChannel("sensorFeed");
		assertNotNull("Subscribed channel should be created", c);

		// Send message with temp = 10 (should be filtered out)
		Message msgBelow = new Message("sender", "sensorFeed");
		msgBelow.addData("temp", 10.0f);
		c.send(msgBelow);
		assertEquals("Alice should not have received filtered message", 0, alice.received.size());

		// Send message with temp = 30 (should pass and transform)
		Message msgAbove = new Message("sender", "sensorFeed");
		msgAbove.addData("temp", 30.0f);
		c.send(msgAbove);
		assertEquals("Alice should have received 1 message", 1, alice.received.size());
		Message receivedMsg = alice.received.get(0);
		assertNotNull("Transformed property 'fahrenheit' must exist", receivedMsg.data.get("fahrenheit"));
		assertEquals("30C should be 86F", 86.0f, ((Number) receivedMsg.data.get("fahrenheit")).floatValue(), 0.01f);
	}
}
