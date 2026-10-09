import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import org.junit.Test;

import controllers.Application;
import model.clients.HTTPRequestClient;
import model.clients.ServiceRequestClient;
import model.codegen.Interactable;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Server;
import nl.tue.id.oocsi.server.protocol.Message;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;
import play.test.WSTestClient;
import play.test.WithServer;

public class WebBackendSecurityTest extends WithServer {

	@Test
	public void testSsrfProtectionInHTTPRequestClient() throws Exception {
		OOCSIServer server = new OOCSIServer();
		final WSClient ws = WSTestClient.newClient(3333);
		HTTPRequestClient client = new HTTPRequestClient("http-test-client", server, ws);

		String[] dangerousUrls = {
			"http://127.0.0.1:8080/secret",
			"http://localhost/admin",
			"http://169.254.169.254/latest/meta-data",
			"http://10.0.0.1/internal",
			"http://192.168.1.1/router",
			"http://172.16.0.1/private",
			"http://[fc00::1]/test",
			"http://[fd12:3456:789a::1]/test",
			"http://[::127.0.0.1]/test",
			"http://[::10.0.0.1]/test",
			"http://[2002:7f00:0001::]/test",
			"http://[2001:db8::1]/test",
			"ftp://example.com/file",
			"file:///etc/passwd"
		};

		for (String dangerousUrl : dangerousUrls) {
			Message m = new Message("testSender", "http-test-client");
			m.addData("url", dangerousUrl);
			boolean accepted = client.send(m);
			assertFalse("Dangerous SSRF URL should be rejected: " + dangerousUrl, accepted);
		}

		ws.close();
		server.stop();
	}

	@Test
	public void testReservedChannelNamesBlockedInHttpApi() throws Exception {
		final WSClient ws = WSTestClient.newClient(3333);
		int port = testServer.getRunningHttpPort().getAsInt();

		// Test GET /track for reserved channel
		WSRequest trackReq = ws.url("http://127.0.0.1:" + port + "/track/SERVER/test");
		WSResponse trackResp = trackReq.get().toCompletableFuture().get();
		assertEquals("Reserved channel on /track must return 400", 400, trackResp.getStatus());

		// Test POST /send for reserved channel
		WSRequest sendReq = ws.url("http://127.0.0.1:" + port + "/send/SERVER");
		WSResponse sendResp = sendReq.post("sender=attacker&data=val").toCompletableFuture().get();
		assertEquals("Reserved channel on /send must return 400", 400, sendResp.getStatus());

		ws.close();
	}

	@Test
	public void testServiceRequestClientUsesUuidAndResets() {
		OOCSIServer server = new OOCSIServer();
		ServiceRequestClient c1 = new ServiceRequestClient(server);
		ServiceRequestClient c2 = new ServiceRequestClient(server);

		assertTrue("Client name should start with prefix", c1.getName().startsWith("serverclient_"));
		assertFalse("Client names should be unique UUIDs", c1.getName().equals(c2.getName()));

		Message m = new Message("test", c1.getName());
		c1.send(m);
		assertTrue("Client should be completed after receiving message", c1.completed());

		c1.reset();
		assertFalse("Client should not be completed after reset", c1.completed());

		server.stop();
	}

	@Test
	public void testInteractableSanitization() {
		Interactable i = new Interactable();
		i.type = "slider";
		i.par = "my bad; variable ()";
		i.def = "123; evil_code()";

		assertEquals("Variable name should only contain safe identifier characters", "mybadvariable", i.getVarName());
		assertEquals("Default value should be parsed as clean integer", "0", i.getDefault());

		i.def = "42";
		assertEquals("Valid default value should be preserved", "42", i.getDefault());

		i.par = "123leadingDigit";
		assertEquals("Variable starting with digit should be prefixed", "v_123leadingDigit", i.getVarName());
	}

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

		// IPv6 ULA (fc00::/7)
		assertFalse("IPv6 ULA fc00::1 must be blocked",
				HTTPRequestClient.isSafeUrl("http://[fc00::1]/test"));
		assertFalse("IPv6 ULA fd12:3456:789a::1 must be blocked",
				HTTPRequestClient.isSafeUrl("http://[fd12:3456:789a::1]/test"));

		// IPv6 IPv4-compatible
		assertFalse("IPv6-compatible loopback must be blocked",
				HTTPRequestClient.isSafeUrl("http://[::127.0.0.1]/test"));
		assertFalse("IPv6-compatible 10.0.0.1 must be blocked",
				HTTPRequestClient.isSafeUrl("http://[::10.0.0.1]/test"));
		assertFalse("IPv6-compatible 169.254.169.254 must be blocked",
				HTTPRequestClient.isSafeUrl("http://[::169.254.169.254]/test"));

		// IPv6 Documentation prefix
		assertFalse("IPv6 documentation prefix must be blocked",
				HTTPRequestClient.isSafeUrl("http://[2001:db8::1]/test"));

		// IPv6 6to4 prefix with embedded private IPv4
		assertFalse("IPv6 6to4 with loopback must be blocked",
				HTTPRequestClient.isSafeUrl("http://[2002:7f00:0001::]/test"));

		// Test redirect configuration
		HTTPRequestClient defaultClient = new HTTPRequestClient("http-test-default", null, null);
		assertFalse("Default followRedirects should be false", defaultClient.isFollowRedirects());
		HTTPRequestClient noRedirectClient = new HTTPRequestClient("http-test-noredirect", null, null, false);
		assertFalse("Explicit followRedirects should be false", noRedirectClient.isFollowRedirects());
		defaultClient.setFollowRedirects(true);
		assertTrue("Setter should update followRedirects", defaultClient.isFollowRedirects());
		defaultClient.setFollowRedirects(false);
		assertFalse("Setter should update followRedirects", defaultClient.isFollowRedirects());
	}

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
