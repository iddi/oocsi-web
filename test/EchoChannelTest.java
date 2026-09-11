import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.LinkedList;
import java.util.List;

import org.junit.Test;

import nl.tue.id.oocsi.OOCSICommunicator;
import nl.tue.id.oocsi.OOCSIEvent;
import play.test.WithServer;

public class EchoChannelTest extends WithServer {

	List<OOCSIEvent> events = new LinkedList<OOCSIEvent>();

	public void handleOOCSIEvent(OOCSIEvent event) {
		events.add(event);
	}

	@Test
	public void testEchoChannel() throws InterruptedException {
		OOCSICommunicator oco = new OOCSICommunicator(this, "in_need_of_echo");
		oco.connect("127.0.0.1", 4444);

		assertTrue(oco.isConnected());
		assertEquals(0, events.size());

		Thread.sleep(100);

		oco.channel("echo").data("testInt", 3).data("testString", "vanilla").send();

		for (int i = 0; i < 20 && events.isEmpty(); i++) {
			Thread.sleep(100);
		}

		assertEquals(1, events.size());
	}
}
