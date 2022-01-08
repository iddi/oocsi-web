package model.clients;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import model.actors.WebSocketClientActor;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;
import nl.tue.id.oocsi.server.protocol.Protocol;
import play.libs.Json;

public class WebSocketClient extends Client {

	private static final ObjectMapper JSON_SERIALIZER = new ObjectMapper();
	private static final Logger logger = LoggerFactory.getLogger(WebSocketClient.class);

	private OOCSIServer server;
	private WebSocketClientActor output;
	private Semaphore pingQueue = new Semaphore(10);

	public WebSocketClient(String token, OOCSIServer server, WebSocketClientActor out) {
		super(token, server.getChangeListener());

		this.server = server;
		this.output = out;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.tue.id.oocsi.server.model.Client#send(nl.tue.id.oocsi.server.protocol.Message)
	 */
	@Override
	public void send(Message message) {
		if (output != null) {
			output.tell(toJson(message));
		}
	}

	/**
	 * send a status message
	 * 
	 * @param code
	 * @param message
	 */
	public void status(int code, String message) {
		Map<String, Object> map = new HashMap<String, Object>();
		map.put("code", code);
		map.put("status", message);
		send(new Message("server", token, new Date(), map));
	}

	/**
	 * forward message to internal OOCSI server
	 * 
	 * @param message
	 */
	public void receive(String message) {
		parseMessage(message);
	}

	/**
	 * internal message parsing (will ignore all except for sendjson, subscribe, and unsubscribe)
	 * 
	 * @param inputLine
	 */
	private void parseMessage(String inputLine) {
		if (inputLine.startsWith("sendjson")) {
			String[] tokens = inputLine.split(" ", 3);
			if (tokens.length == 3) {
				String recipient = tokens[1];
				String data = tokens[2];
				Channel c = server.getChannel(recipient);
				if (c != null) {
					Map<String, Object> map = Protocol.parseJSONMessage(data);
					c.send(new Message(token, recipient, new Date(), map));
				}
			}
		} else if (inputLine.startsWith("subscribe")) {
			String[] tokens = inputLine.split(" ", 2);
			if (tokens.length == 2) {
				String channel = tokens[1];
				server.subscribe(this, channel);
			}
		} else if (inputLine.startsWith("unsubscribe")) {
			String[] tokens = inputLine.split(" ", 2);
			if (tokens.length == 2) {
				String channel = tokens[1];
				server.unsubscribe(this, channel);
			}
		} else {
			// ignore all other messages, do nothing
			server.refreshChannelPresence(this);
			pong();
		}

		// update last action
		this.touch();
	}

	@Override
	public void ping() {
		if (output != null && pingQueue.tryAcquire()) {
			output.tell("ping");
		}
	}

	@Override
	public void pong() {
		pingQueue.release();
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public void disconnect() {
		output.kill();
		server.removeClient(this);
	}

	private String toJson(Message m) {
		ObjectNode je = Json.newObject();

		// add OOCSI properties
		je.put("recipient", m.recipient);
		je.put("timestamp", m.timestamp.getTime());
		je.put("sender", m.sender);

		je.set("data", JSON_SERIALIZER.valueToTree(m.data));

		// serialize
		return je.toString();
	}
}
