package model.clients;

import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;

public class EchoClient extends Client {

	final private OOCSIServer server;

	public EchoClient(String token, OOCSIServer server) {
		super(token, server.getChangeListener());

		this.server = server;
		server.addClient(this);
	}

	@Override
	public void disconnect() {
		server.removeClient(this);
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public void ping() {
		// do nothing
	}

	@Override
	public void pong() {
		// do nothing
	}

	@Override
	public long lastAction() {
		return System.currentTimeMillis();
	}

	@Override
	public void send(Message event) {
		if (validate(event.getRecipient())) {
			Message m = new Message("echo", event.getSender());
			m.data.putAll(event.data);
			Channel c = server.getChannel(event.getSender());
			if (c != null) {
				c.send(m);

				// log access
				OOCSIServer.logEvent(token, "", event.getSender(), event.data, event.getTimestamp());
			}
		}
	}

}
