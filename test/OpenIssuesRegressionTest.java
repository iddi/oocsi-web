import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import controllers.Application;
import model.clients.HTTPRequestClient;
import nl.tue.id.oocsi.client.OOCSIClient;
import nl.tue.id.oocsi.client.behavior.OOCSISpatial;
import nl.tue.id.oocsi.client.socket.SocketClient;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.model.FunctionClient;
import nl.tue.id.oocsi.server.model.Server;
import nl.tue.id.oocsi.server.protocol.Message;

public class OpenIssuesRegressionTest {

	private Server server;
	private MockClient alice;
	private MockClient bob;

	private static class MockClient extends Client {
		List<Message> received = new ArrayList<>();
		boolean connected = true;

		public MockClient(String token, Channel.ChangeListener listener) {
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
		alice = new MockClient("alice", server.getChangeListener());
		bob = new MockClient("bob", server.getChangeListener());
		server.addClient(alice);
		server.addClient(bob);
	}


	/**
	 * OPEN-H1, OPEN-L5: Multicast discovery removed and neutralized
	 */
	@Test
	public void testMulticastDisabledInClient() {
		// SocketClient.startMulticastLookup() should safely return false without throwing exceptions
		SocketClient socketClient = new SocketClient("testToken", null, null);
		assertFalse("startMulticastLookup must return false since multicast is removed",
				socketClient.startMulticastLookup());

		// OOCSIClient.connect() without host/port should return false
		OOCSIClient client = new OOCSIClient("mcastTestClient");
		assertFalse("connect() without arguments must return false since multicast is removed",
				client.connect());
		assertFalse("Client should remain unconnected", client.isConnected());
	}

	/**
	 * OPEN-M1: Reserved channel filtering in Application.filterChannelList
	 */
	@Test
	public void testReservedChannelFiltering() {
		String rawChannels = "myPublicChannel, OOCSI_events, SERVER, OOCSI_connections, OOCSI_channels, OOCSI_clients, metrics, track, anotherChannel";
		String filtered = Application.filterChannelList(rawChannels);

		assertTrue("Public channel should be present", filtered.contains("myPublicChannel"));
		assertTrue("Public channel should be present", filtered.contains("anotherChannel"));

		for (String reserved : Server.RESERVED_NAMES) {
			assertFalse("Reserved name " + reserved + " must not appear in filtered channels",
					filtered.contains(reserved));
		}

		// Null and empty safety
		assertEquals("", Application.filterChannelList(null));
		assertEquals("", Application.filterChannelList(""));
		assertEquals("", Application.filterChannelList("   "));
	}

	/**
	 * OPEN-M5: UUID validation for cookie userId in Application
	 */
	@Test
	public void testExtractUserIdValidation() {
		// Valid UUID passes unchanged
		String validUuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
		assertEquals(validUuid, Application.validateOrCreateUserId(validUuid));

		// Path traversal attempt should be rejected and replaced with valid UUID
		String badPath = "../../etc/passwd";
		String resultPath = Application.validateOrCreateUserId(badPath);
		assertFalse("Path traversal must not be used as userId", resultPath.equals(badPath));
		assertTrue("Fallback must be valid UUID", Application.UUID_RE.matcher(resultPath).matches());

		// XSS / script attempt should be rejected and replaced with valid UUID
		String badXss = "<script>alert('xss')</script>";
		String resultXss = Application.validateOrCreateUserId(badXss);
		assertFalse("XSS payload must not be used as userId", resultXss.equals(badXss));
		assertTrue("Fallback must be valid UUID", Application.UUID_RE.matcher(resultXss).matches());

		// SQL injection attempt should be rejected and replaced with valid UUID
		String badSql = "admin' OR '1'='1";
		String resultSql = Application.validateOrCreateUserId(badSql);
		assertFalse("SQL injection must not be used as userId", resultSql.equals(badSql));
		assertTrue("Fallback must be valid UUID", Application.UUID_RE.matcher(resultSql).matches());

		// Null / empty should generate valid UUID
		String resultNull = Application.validateOrCreateUserId(null);
		assertTrue("Null input should generate valid UUID", Application.UUID_RE.matcher(resultNull).matches());
	}

	/**
	 * OPEN-M1: /? subscription query protection on reserved & private channels
	 */
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

	/**
	 * OPEN-H4: FunctionClient EvalEx dangerous function blocking
	 */
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

	/**
	 * OPEN-H6: SSRF URL validation in HTTPRequestClient
	 */
	@Test
	public void testSsrfProtectionClassEAndIpv6Mapped() {
		// Class E addresses (240.0.0.0/4)
		assertFalse("Class E IP 240.0.0.1 must be blocked",
				HTTPRequestClient.isSafeUrl("http://240.0.0.1/test"));
		assertFalse("Class E IP 250.1.2.3 must be blocked",
				HTTPRequestClient.isSafeUrl("http://250.1.2.3/test"));
		assertFalse("Broadcast IP 255.255.255.255 must be blocked",
				HTTPRequestClient.isSafeUrl("http://255.255.255.255/test"));

		// IPv6-mapped IPv4 addresses
		assertFalse("IPv6-mapped loopback must be blocked",
				HTTPRequestClient.isSafeUrl("http://[::ffff:127.0.0.1]/test"));
		assertFalse("IPv6-mapped 10.0.0.0/8 must be blocked",
				HTTPRequestClient.isSafeUrl("http://[::ffff:10.0.0.1]/test"));
		assertFalse("IPv6-mapped 192.168.0.0/16 must be blocked",
				HTTPRequestClient.isSafeUrl("http://[::ffff:192.168.1.1]/test"));
		assertFalse("IPv6-mapped Class E must be blocked",
				HTTPRequestClient.isSafeUrl("http://[::ffff:240.0.0.1]/test"));

		// Loopback and RFC 1918
		assertFalse("127.0.0.1 must be blocked",
				HTTPRequestClient.isSafeUrl("http://127.0.0.1:8080/admin"));
		assertFalse("localhost must be blocked",
				HTTPRequestClient.isSafeUrl("http://localhost/status"));
		assertFalse("10.0.0.1 must be blocked",
				HTTPRequestClient.isSafeUrl("http://10.0.0.1/internal"));
		assertFalse("192.168.1.1 must be blocked",
				HTTPRequestClient.isSafeUrl("http://192.168.1.1/router"));
		assertFalse("172.16.0.1 must be blocked",
				HTTPRequestClient.isSafeUrl("http://172.16.0.1/private"));
		assertFalse("AWS metadata must be blocked",
				HTTPRequestClient.isSafeUrl("http://169.254.169.254/latest/meta-data"));

		// Non-HTTP schemes
		assertFalse("FTP scheme must be blocked",
				HTTPRequestClient.isSafeUrl("ftp://example.com/file"));
		assertFalse("FILE scheme must be blocked",
				HTTPRequestClient.isSafeUrl("file:///etc/passwd"));
		assertFalse("GOPHER scheme must be blocked",
				HTTPRequestClient.isSafeUrl("gopher://example.com/"));

		// Test redirect configuration
		HTTPRequestClient defaultClient = new HTTPRequestClient("http-test-default", server, null);
		assertTrue("Default followRedirects should be true", defaultClient.isFollowRedirects());
		HTTPRequestClient noRedirectClient = new HTTPRequestClient("http-test-noredirect", server, null, false);
		assertFalse("Explicit followRedirects should be false", noRedirectClient.isFollowRedirects());
		defaultClient.setFollowRedirects(false);
		assertFalse("Setter should update followRedirects", defaultClient.isFollowRedirects());
	}

	/**
	 * OPEN-L4: OOCSISpatial volatile metric field concurrency
	 */
	@Test
	public void testSpatialMetricVolatileField() throws Exception {
		java.lang.reflect.Field metricField = OOCSISpatial.class.getDeclaredField("metric");
		assertTrue("Field metric in OOCSISpatial must be volatile to prevent thread race conditions",
				java.lang.reflect.Modifier.isVolatile(metricField.getModifiers()));
	}

	/**
	 * XSS Prevention & Client Name Validation
	 */
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

	/**
	 * Channel Name Validation
	 */
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

	/**
	 * Subscription Syntax Validation (Presence, Filter, Transform, Chains)
	 */
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

	/**
	 * Server-level Client and Subscription Enforcement
	 */
	@Test
	public void testServerLevelEnforcement() {
		// XSS client handle rejected by addClient
		MockClient xssClient = new MockClient("<script>alert(1)</script>", server.getChangeListener());
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

	/**
	 * Test that dashboard template defines getNestedValue in script scope accessible to addChart
	 */
	@Test
	public void testDashboardTemplateDefinesGetNestedValueInAddChartScope() {
		play.twirl.api.Html rendered = views.html.Tools.dashboard.render("dashboard", "", "localhost");
		String body = rendered.body();

		// Check that getNestedValue is defined
		assertTrue("Dashboard should contain getNestedValue definition",
				body.contains("function getNestedValue(obj, path)"));

		// Check that getNestedValue is defined before addChart and outside of $(document).ready
		int readyIdx = body.indexOf("$(document).ready");
		int readyCloseIdx = body.indexOf("});", readyIdx);
		int getNestedValueIdx = body.indexOf("function getNestedValue(obj, path)");
		int addChartIdx = body.indexOf("function addChart(channel, selector)");

		assertTrue("$(document).ready must be present", readyIdx != -1 && readyCloseIdx != -1);
		assertTrue("getNestedValue must be present", getNestedValueIdx != -1);
		assertTrue("addChart must be present", addChartIdx != -1);

		assertTrue("getNestedValue must not be trapped inside $(document).ready block",
				getNestedValueIdx > readyCloseIdx);
		assertTrue("getNestedValue must be defined before addChart",
				getNestedValueIdx < addChartIdx);
	}
}

