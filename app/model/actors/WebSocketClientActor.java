package model.actors;

import java.util.Random;

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
				// assuming the event contains the client handle
				String clientName = event;

				// remove any whitespace at begin and end
				clientName = clientName.trim();

				// check input line for exceptional values that cannot be handled safely
				// do some filtering for SSH clients connecting and other abuse
				if (clientName.length() > 200) {
					OOCSIServer.log("Killed client connection for [length]: " + clientName);
					clientName = "webclient_####";
				}
				if (!clientName.matches("\\p{ASCII}+$")) {
					OOCSIServer.log("Killed client connection for [non-ASCII chars]: " + clientName);
					clientName = "webclient_####";
				}
				if (clientName.matches(".*\\s.*")) {
					OOCSIServer.log("Killed client connection because client name contains whitespace characters: "
					        + clientName);
					clientName = "webclient_####";
				}

				// check input line for workable deviations from protocol
				// remove starting or trailing slashes
				clientName = clientName.replaceAll("^/|/$", "");

				// if there are one or more hashes in the inputLine, we need to generate a client name
				for (int i = 0; i < 20 && clientName.contains("#"); i++) {
					String tempHandle = replaceHashesWithDigits(clientName);
					if (server.getClient(tempHandle) == null) {
						clientName = tempHandle;
						break;
					}
				}

				// create client with the processed, validated and sanitized name
				client = new WebSocketClient(clientName, server, this);
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

	private String replaceHashesWithDigits(String input) {
		StringBuilder result = new StringBuilder(input.length());
		Random RAND = new Random();
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == '#') {
				result.append(RAND.nextInt(10));
			} else {
				result.append(c);
			}
		}
		return result.toString();
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
