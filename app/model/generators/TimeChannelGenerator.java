package model.generators;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorSystem;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.protocol.Message;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;

public class TimeChannelGenerator {

	private static final String CHANNEL = "timechannel";
	private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

	final private OOCSIServer server;
	final private ActorSystem actorSystem;
	final private ExecutionContext executionContext;

	public TimeChannelGenerator(OOCSIServer server, ActorSystem system, ExecutionContext ectx) {
		this.server = server;
		this.actorSystem = system;
		this.executionContext = ectx;

		this.initialize();
	}

	private void initialize() {
		this.actorSystem.scheduler().scheduleAtFixedRate(Duration.create(2, TimeUnit.SECONDS),
		        Duration.create(1, TimeUnit.SECONDS), () -> publish(), this.executionContext);
	}

	private void publish() {
		Message m = new Message("SERVER", CHANNEL);

		final Calendar cal = new GregorianCalendar();
		m.addData("y", cal.get(Calendar.YEAR));
		m.addData("M", cal.get(Calendar.MONTH));
		m.addData("d", cal.get(Calendar.DAY_OF_MONTH));
		m.addData("dw", cal.get(Calendar.DAY_OF_WEEK));
		m.addData("h", cal.get(Calendar.HOUR_OF_DAY));
		m.addData("m", cal.get(Calendar.MINUTE));
		m.addData("s", cal.get(Calendar.SECOND));
		m.addData("timestamp", cal.getTimeInMillis());
		m.addData("datetime", sdf.format(cal.getTime()));

		Channel channel = server.getChannel(CHANNEL);
		if (channel != null) {
			channel.send(m);
		}
	}
}
