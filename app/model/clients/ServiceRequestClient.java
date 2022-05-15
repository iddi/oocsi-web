package model.clients;

import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;

public class ServiceRequestClient extends Client {

	public Message completedMessage = null;

	public ServiceRequestClient(OOCSIServer server) {
		super("serverclient" + Math.random(), server.getChangeListener());
	}

	@Override
	public void send(Message message) {
		if (validate(message.getRecipient())) {
			completedMessage = message;
		}
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
}