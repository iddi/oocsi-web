package model.clients;

import java.util.UUID;
import java.util.function.Consumer;

import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;

public class ServiceRequestClient extends Client {

	public volatile Message completedMessage = null;
	private volatile Consumer<Message> responseCallback = null;

	public ServiceRequestClient(OOCSIServer server) {
		super("serverclient_" + UUID.randomUUID().toString(), server.getChangeListener());
	}

	public void setResponseCallback(Consumer<Message> callback) {
		this.responseCallback = callback;
	}

	@Override
	public boolean send(Message message) {
		if (!validate(message.getRecipient())) {
			return false;
		}

		completedMessage = message;
		Consumer<Message> cb = responseCallback;
		if (cb != null) {
			cb.accept(message);
		}
		return true;
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public void disconnect() {
		// do nothing
	}

	@Override
	public void ping() {
		// do nothing
	}

	@Override
	public void pong() {
		// do nothing
	}

	public boolean completed() {
		return completedMessage != null;
	}

	public void reset() {
		completedMessage = null;
		responseCallback = null;
	}
}