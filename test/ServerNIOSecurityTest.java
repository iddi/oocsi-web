import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.Test;

import play.test.WithServer;

public class ServerNIOSecurityTest extends WithServer {

	@Test
	public void testPreAuthBufferExceededDisconnects() throws Exception {
		try (Socket socket = new Socket("127.0.0.1", 4444)) {
			socket.setSoTimeout(3000);
			OutputStream out = socket.getOutputStream();

			// Send more than 256 bytes without newline
			byte[] payload = new byte[300];
			Arrays.fill(payload, (byte) 'A');
			out.write(payload);
			out.flush();

			// Verify server closes connection
			BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			int read = reader.read();
			assertEquals("Connection should be closed on excessive pre-auth buffer", -1, read);
		}
	}

	@Test
	public void testPostAuthBufferExceededDisconnects() throws Exception {
		try (Socket socket = new Socket("127.0.0.1", 4444)) {
			socket.setSoTimeout(3000);
			OutputStream out = socket.getOutputStream();
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

			// Connect and register
			out.write("test_post_auth_client\n".getBytes(StandardCharsets.UTF_8));
			out.flush();

			// Read welcome message
			String welcome = in.readLine();
			assertNotNull("Should receive welcome message", welcome);

			// Send more than 65536 bytes without newline
			byte[] payload = new byte[70000];
			Arrays.fill(payload, (byte) 'x');
			out.write(payload);
			out.flush();

			// Verify server disconnects the socket
			int ch;
			try {
				ch = in.read();
			} catch (Exception e) {
				ch = -1;
			}
			assertEquals("Connection should be terminated when post-auth buffer limit exceeded", -1, ch);
		}
	}

	@Test
	public void testHashReplacementGeneratesUniqueClients() throws Exception {
		try (Socket socket1 = new Socket("127.0.0.1", 4444);
		     Socket socket2 = new Socket("127.0.0.1", 4444)) {
			socket1.setSoTimeout(3000);
			socket2.setSoTimeout(3000);

			OutputStream out1 = socket1.getOutputStream();
			BufferedReader in1 = new BufferedReader(new InputStreamReader(socket1.getInputStream(), StandardCharsets.UTF_8));
			out1.write("gen_###\n".getBytes(StandardCharsets.UTF_8));
			out1.flush();
			String welcome1 = in1.readLine();
			assertNotNull("Should receive welcome message for socket 1", welcome1);
			assertTrue("Welcome 1 should match pattern", welcome1.matches(".*welcome gen_\\d{3}.*"));

			OutputStream out2 = socket2.getOutputStream();
			BufferedReader in2 = new BufferedReader(new InputStreamReader(socket2.getInputStream(), StandardCharsets.UTF_8));
			out2.write("gen_###\n".getBytes(StandardCharsets.UTF_8));
			out2.flush();
			String welcome2 = in2.readLine();
			assertNotNull("Should receive welcome message for socket 2", welcome2);
			assertTrue("Welcome 2 should match pattern", welcome2.matches(".*welcome gen_\\d{3}.*"));

			assertNotEquals("Generated client handles should be distinct", welcome1, welcome2);
		}
	}
}
