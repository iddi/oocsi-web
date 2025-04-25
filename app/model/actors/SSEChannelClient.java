package model.actors;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;

import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;
import play.libs.Json;

public class SSEChannelClient extends Client {

	private Queue<JsonNode> events = new LinkedBlockingQueue<JsonNode>(10);

	public SSEChannelClient(String channelName) {
		super(channelName, null);
	}

	@Override
	public boolean send(Message message) {

		// drain the queue until we can enter elements again
		while (events.size() > 9) {
			events.poll();
		}

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
