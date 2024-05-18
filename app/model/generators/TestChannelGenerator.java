package model.generators;

import java.util.concurrent.TimeUnit;

import org.apache.pekko.actor.ActorSystem;

import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.protocol.Message;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;

public class TestChannelGenerator {

	private static final String CHANNEL = "testchannel";

	final private OOCSIServer server;
	final private ActorSystem actorSystem;
	final private ExecutionContext executionContext;

	private int frameCount = 0;

	public TestChannelGenerator(OOCSIServer server, ActorSystem system, ExecutionContext ectx) {
		this.server = server;
		this.actorSystem = system;
		this.executionContext = ectx;

		this.initialize();
	}

	private void initialize() {
		this.actorSystem.scheduler().scheduleAtFixedRate(Duration.create(2, TimeUnit.SECONDS),
		        Duration.create(100, TimeUnit.MILLISECONDS), () -> publish(), this.executionContext);
	}

	private void publish() {
		Message m = new Message("SERVER", CHANNEL);

		m.addData("color", 90 + Math.round(Math.sin(frameCount / 20.) * 70));
		m.addData("position", 90 + Math.round(Math.cos(frameCount / 25.) * 70));

		Channel channel = server.getChannel(CHANNEL);
		if (channel != null) {
			channel.send(m);
		}

		frameCount++;
	}
}
