package model.clients;

import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.tue.id.oocsi.client.services.OOCSICall;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;

public class HTTPRequestClient extends Client {

	private static final Logger logger = LoggerFactory.getLogger(HTTPRequestClient.class);

	private OOCSIServer server;
	private WSClient wsClient;
	private long lastExternalRequest = System.currentTimeMillis();

	public HTTPRequestClient(String token, OOCSIServer server, WSClient wsClient) {
		super(token, server.getChangeListener());

		this.server = server;
		this.wsClient = wsClient;
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

		// throttle to 5 requests per second
		if (lastExternalRequest > System.currentTimeMillis() - 500) {
			return;
		}
		lastExternalRequest = System.currentTimeMillis();

		// extract message data
		final String url;
		if (event.data.containsKey("url")) {
			url = ((String) event.data.get("url")).trim();
		} else {
			url = "";
		}

		final String method;
		if (event.data.containsKey("method")) {
			method = ((String) event.data.get("method")).trim();
		} else {
			method = "get";
		}

		final String postBody;
		if (event.data.containsKey("body")) {
			postBody = ((String) event.data.get("body")).trim();
		} else {
			postBody = "";
		}

		final String postJson;
		if (event.data.containsKey("json")) {
			postJson = ((String) event.data.get("json")).trim();
		} else {
			postJson = "";
		}

		// abort if key data is missing
		if (url.isEmpty()) {
			return;
		}

		// log and make the call
		logger.info("Calling http-web-request for URL " + url + " with method " + method + " by " + event.sender);
		try {
			WSRequest request = wsClient.url(url);
			final CompletionStage<WSResponse> wsResponse;
			if (method.equals("post")) {
				if (!postBody.isEmpty()) {
					wsResponse = request.setContentType("application/x-www-form-urlencoded").post(postBody);
				} else {
					wsResponse = request.setContentType("application/json").post(postJson);
				}
			} else {
				wsResponse = request.get();
			}
			wsResponse.thenAccept(response -> {
				if (validate(event.recipient)) {
					Message m = new Message("http-web-request", event.sender);
					m.data.putAll(event.data);
					m.data.put("result-status", response.getStatus());
					m.data.put("result-body", response.getBody());
					m.data.put("result-content-type", response.getContentType());
					if (event.data.containsKey(OOCSICall.MESSAGE_ID)) {
						m.data.put(OOCSICall.MESSAGE_ID, event.data.get(OOCSICall.MESSAGE_ID));
					}

					Channel c = server.getChannel(event.sender);
					if (c != null) {
						c.send(m);

						// log access
						OOCSIServer.logEvent(token, "", event.sender, event.data, event.timestamp);
					}
				}
			}).exceptionally(e -> {
				logger.error("Problem calling http-web-request for URL " + url + " with method " + method + " by "
				        + event.sender + ": " + e.getLocalizedMessage());
				return null;
			});
		} catch (Exception e) {
			logger.error("Problem calling http-web-request for URL " + url + " with method " + method + " by "
			        + event.sender + ": " + e.getLocalizedMessage());
			if (validate(event.recipient)) {
				Message m = new Message("http-web-request", event.sender);
				m.data.putAll(event.data);
				m.data.put("result-status", 404);
				m.data.put("result-body", "The URL seems to be malformed.");
				m.data.put("result-content-type", "");
				Channel c = server.getChannel(event.sender);
				if (c != null) {
					c.send(m);

					// log access
					OOCSIServer.logEvent(token, "", event.sender, event.data, event.timestamp);
				}
			}
		}

	}

}
