import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import nl.tue.id.oocsi.server.OOCSIServer;

public abstract class ClientTestBase {

	protected static OOCSIServer server;

	@BeforeClass
	public static void setUpServer() throws Exception {
		OOCSIServer existing = OOCSIServer.getInstance();
		if (existing != null) {
			existing.stop();
			Thread.sleep(100);
		}
		server = new OOCSIServer(4444, 1000, false);
		Thread.sleep(100);
	}

	@After
	public void tearDown() throws Exception {
		Thread.sleep(100);
	}

	@AfterClass
	public static void tearDownServer() throws Exception {
		if (server != null) {
			server.stop();
			server = null;
			Thread.sleep(100);
		}
	}
}
