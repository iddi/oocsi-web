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
}
