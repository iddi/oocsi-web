import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import nl.tue.id.oocsi.client.OOCSIClient;
import nl.tue.id.oocsi.client.behavior.OOCSISpatial;
import nl.tue.id.oocsi.client.socket.SocketClient;
import play.test.WithServer;

public class ClientSecurityTest extends WithServer {

	@Test
	public void testHashClientNameConnectedWithAssignedName() throws Exception {
		OOCSIClient client = new OOCSIClient("hash_client_####");
		boolean connected = client.connect("127.0.0.1", 4444);

		// Wait up to 2 seconds for connection
		for (int i = 0; !client.isConnected() && i < 20; i++) {
			Thread.sleep(100);
		}

		assertTrue("Client with hash placeholder should connect successfully", client.isConnected());
		String assignedName = client.getName();
		assertNotNull("Assigned name should not be null", assignedName);
		assertFalse("Assigned name should not contain hash characters", assignedName.contains("#"));
		assertTrue("Assigned name should start with base name", assignedName.startsWith("hash_client_"));
		assertTrue("Assigned name should end with 4 digits", assignedName.matches("^hash_client_\\d{4}$"));

		client.disconnect();
	}

	@Test
	public void testTransientNameCollisionDoesNotKillClient() throws Exception {
		OOCSIClient c1 = new OOCSIClient("fixed_name_test");
		c1.connect("127.0.0.1", 4444);

		for (int i = 0; !c1.isConnected() && i < 20; i++) {
			Thread.sleep(100);
		}
		assertTrue("First client should connect", c1.isConnected());

		// Second client attempts connection with duplicate name
		OOCSIClient c2 = new OOCSIClient("fixed_name_test");
		c2.connect("127.0.0.1", 4444);
		Thread.sleep(300);

		// Second client should be rejected transiently
		assertFalse("Duplicate name should not be connected", c2.isConnected());

		// Disconnect first client to free up the name
		c1.disconnect();
		Thread.sleep(500);

		// Second client should now be able to connect
		boolean reconnected = c2.connect("127.0.0.1", 4444);
		for (int i = 0; !c2.isConnected() && i < 20; i++) {
			Thread.sleep(100);
		}
		assertTrue("Second client should connect after name is freed", c2.isConnected());

		c2.disconnect();
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
	 * OPEN-L4: OOCSISpatial volatile metric field concurrency
	 */
	@Test
	public void testSpatialMetricVolatileField() throws Exception {
		java.lang.reflect.Field metricField = OOCSISpatial.class.getDeclaredField("metric");
		assertTrue("Field metric in OOCSISpatial must be volatile to prevent thread race conditions",
				java.lang.reflect.Modifier.isVolatile(metricField.getModifiers()));
	}
}
