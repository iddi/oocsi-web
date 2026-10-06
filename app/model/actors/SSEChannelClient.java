package model.actors;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pekko.stream.javadsl.SourceQueueWithComplete;

import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;
import play.libs.EventSource;
import play.libs.Json;

public class SSEChannelClient extends Client {

	private final SourceQueueWithComplete<EventSource.Event> queue;
	private Queue<JsonNode> events = new LinkedBlockingQueue<JsonNode>(10);

	public SSEChannelClient(String channelName) {
		this(channelName, null);
	}

	public SSEChannelClient(String channelName, SourceQueueWithComplete<EventSource.Event> queue) {
		super(channelName, null);
		this.queue = queue;
	}

	@Override
	public boolean send(Message message) {
		touch();

		if (queue != null) {
			queue.offer(EventSource.Event.event(Json.toJson(message.data)));
			return true;
		}

		// drain the queue until we can enter elements again
		while (events.size() > 9) {
			events.poll();
		}

		return events.offer(Json.toJson(message.data));
	}

	@Override
	public void disconnect() {
		if (queue != null) {
			try {
				queue.complete();
			} catch (Exception ignored) {
			}
		}
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
