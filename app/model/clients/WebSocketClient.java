package model.clients;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import model.actors.WebSocketClientActor;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;
import nl.tue.id.oocsi.server.protocol.Protocol;
import play.libs.Json;

public class WebSocketClient extends Client {

	private final ObjectMapper JSON_OBJECT_MAPPER;
//	private static final Logger logger = LoggerFactory.getLogger(WebSocketClient.class);

	private OOCSIServer server;
	private WebSocketClientActor output;
	private Semaphore pingQueue = new Semaphore(10);

	public WebSocketClient(String token, OOCSIServer server, WebSocketClientActor out) {
		super(token, server.getChangeListener());

		this.server = server;
		this.output = out;

		this.JSON_OBJECT_MAPPER = JsonMapper.builder().configure(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST, false)
		        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
		        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).build();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see nl.tue.id.oocsi.server.model.Client#send(nl.tue.id.oocsi.server.protocol.Message)
	 */
	@Override
	public boolean send(Message message) {
		if (output == null) {
			return false;
		}

		output.tell(toJson(message));
		return true;
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
		je.put("recipient", m.getRecipient());
		je.put("timestamp", m.getTimestamp().getTime());
		je.put("sender", m.getSender());

		je.set("data", JSON_OBJECT_MAPPER.valueToTree(m.data));

		// serialize message
		try {
			return JSON_OBJECT_MAPPER.writeValueAsString(je);
		} catch (JsonProcessingException e) {
			// fall back to normal toString
			return je.toString();
		}
	}
}
