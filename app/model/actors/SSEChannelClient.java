package model.actors;

import java.util.LinkedList;
import java.util.Queue;

import com.fasterxml.jackson.databind.JsonNode;

import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;
import play.libs.Json;

public class SSEChannelClient extends Client {

	private Queue<JsonNode> events = new LinkedList<JsonNode>();

	public SSEChannelClient(String channelName) {
		super(channelName, null);
	}

	@Override
	public boolean send(Message message) {
		return events.offer(Json.toJson(message.data));
	}

	@Override
	public void disconnect() {
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public void ping() {
	}

	@Override
	public void pong() {
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	public JsonNode poll() {
		return events.poll();
	}

	public boolean isEmpty() {
		return events.isEmpty();
	}

}
