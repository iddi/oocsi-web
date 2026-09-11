import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import org.junit.Test;

import model.clients.HTTPRequestClient;
import model.clients.ServiceRequestClient;
import model.codegen.Interactable;
import nl.tue.id.oocsi.server.OOCSIServer;
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
}
