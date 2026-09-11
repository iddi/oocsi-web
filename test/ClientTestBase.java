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
		}
		server = new OOCSIServer(4444, 1000, false);
	}

	@AfterClass
	public static void tearDownServer() {
		if (server != null) {
			server.stop();
			server = null;
		}
	}
}
