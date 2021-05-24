package model.clients;

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import model.actors.WebSocketClientActor;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;
import play.libs.Json;

public class WebSocketClient extends Client {

	private static final Gson GSON = new Gson();
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
				try {
					JsonNode node = Json.parse(data);
					if (node.isObject()) {
						Channel c = server.getChannel(recipient);
						if (c != null) {
							Map<String, Object> map = new HashMap<String, Object>();
							ObjectNode on = (ObjectNode) node;
							for (Iterator<Entry<String, JsonNode>> iterator = on.fields(); iterator.hasNext();) {
								Entry<String, JsonNode> entry = iterator.next();
								JsonNode val = entry.getValue();
								if (val.isBoolean()) {
									map.put(entry.getKey(), val.booleanValue());
								} else if (val.isInt()) {
									map.put(entry.getKey(), val.intValue());
								} else if (val.isFloat()) {
									map.put(entry.getKey(), val.floatValue());
								} else if (val.isDouble()) {
									map.put(entry.getKey(), val.doubleValue());
								} else if (val.isLong()) {
									map.put(entry.getKey(), val.longValue());
								} else if (val.isTextual()) {
									map.put(entry.getKey(), val.textValue());
								} else if (val.isObject()) {
									ObjectNode object = (ObjectNode) val;
									map.put(entry.getKey(), object.toString());
								} else if (val.isArray()) {
									ArrayNode array = (ArrayNode) val;
									JsonNode jnn = array.get(0);
									if (jnn.isBoolean()) {
										// proceed with boolean array
										boolean[] ba = new boolean[array.size()];
										for (int i = 0; i < array.size(); i++) {
											ba[i] = array.get(i).asBoolean();
										}
										map.put(entry.getKey(), ba);
									} else if (jnn.isInt() || jnn.isLong()) {
										// proceed with int array
										int[] ba = new int[array.size()];
										for (int i = 0; i < array.size(); i++) {
											ba[i] = array.get(i).asInt();
										}
										map.put(entry.getKey(), ba);
									} else if (jnn.isFloat() || jnn.isDouble()) {
										// proceed with float array
										double[] ba = new double[array.size()];
										for (int i = 0; i < array.size(); i++) {
											ba[i] = array.get(i).asDouble();
										}
										map.put(entry.getKey(), ba);
									} else if (jnn.isTextual()) {
										// proceed with string array
										String[] ba = new String[array.size()];
										for (int i = 0; i < array.size(); i++) {
											ba[i] = array.get(i).asText();
										}
										map.put(entry.getKey(), ba);
									} else {
										map.put(entry.getKey(), array.toString());
									}
								}
							}
							c.send(new Message(token, recipient, new Date(), map));
						}
					}
				} catch (Exception e) {
					// outputStream.write("ERROR: parse exception");
					logger.warn("JSON parse exception", e);
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
		JsonObject jo = new JsonObject();
		jo.addProperty("sender", m.sender);
		jo.addProperty("recipient", m.recipient);
		jo.addProperty("timestamp", m.timestamp.getTime());
		jo.add("data", GSON.toJsonTree(m.data));

		return jo.toString();
	}
}
