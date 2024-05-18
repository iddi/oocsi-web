package model.actors;

import org.apache.pekko.actor.AbstractActor;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.PoisonPill;
import org.apache.pekko.actor.Props;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;

import model.clients.WebSocketClient;
import nl.tue.id.oocsi.server.OOCSIServer;

public class WebSocketClientActor extends AbstractActor {

	private static final Logger logger = LoggerFactory.getLogger(WebSocketClientActor.class);

	public static Props props(ActorRef out, OOCSIServer server) {
		return Props.create(WebSocketClientActor.class, out, server);
	}

	private final ActorRef out;
	private final OOCSIServer server;
	private WebSocketClient client;

	/**
	 * create a websocket client actor that relays messages between the websocket and the OOCSI server
	 * 
	 * @param out
	 */
	@Inject
	public WebSocketClientActor(ActorRef out, OOCSIServer server) {
		this.out = out;
		this.server = server;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see akka.actor.AbstractActor#createReceive()
	 */
	@Override
	public Receive createReceive() {
		return receiveBuilder().match(String.class, event -> {
			if (client == null) {
				client = new WebSocketClient(event, server, this);
				if (server.addClient(client)) {
					logger.info("WS client " + client.getName() + " connected");
					// status(200, );
					out.tell("{\"message\" : \"welcome " + client.getName() + "\"}", self());
				} else {
					logger.info("WS client " + client.getName() + " rejected as existing");
					// status(401, );
					out.tell("{\"message\" : \"ERROR: client " + client.getName() + " exists already\"}", self());

					// kill self
					kill();
				}
			}

			// anything useful?
			client.receive(event);
		}).build();
	}

	/**
	 * terminate this websocket client (and connection)
	 * 
	 */
	public void kill() {
		self().tell(PoisonPill.getInstance(), self());
	}

	/**
	 * forward message to actor and connected client
	 * 
	 * @param message
	 */
	public void tell(String message) {
		out.tell(message, self());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see akka.actor.AbstractActor#postStop()
	 */
	public void postStop() throws Exception {
		if (client != null) {
			client.disconnect();
			logger.info("WS client " + client.getName() + " disconnected");
		}
	}
}
