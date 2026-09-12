package model.clients;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
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

	private static final int MAX_RESPONSE_BODY_LENGTH = 65536;

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

	public static boolean isSafeUrl(String urlStr) {
		try {
			URI uri = new URI(urlStr);
			String scheme = uri.getScheme();
			if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
				return false;
			}
			String host = uri.getHost();
			if (host == null || host.trim().isEmpty()) {
				return false;
			}
			host = host.trim().toLowerCase();
			if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
					|| host.endsWith(".internal")) {
				return false;
			}

			InetAddress[] addresses = InetAddress.getAllByName(host);
			for (InetAddress addr : addresses) {
				if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
						|| addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
					return false;
				}
				byte[] bytes = addr.getAddress();
				if (bytes.length == 4) {
					int b0 = bytes[0] & 0xFF;
					int b1 = bytes[1] & 0xFF;
					if (b0 == 0 || b0 == 10 || b0 == 127) {
						return false;
					}
					if (b0 == 169 && b1 == 254) {
						return false;
					}
					if (b0 == 172 && (b1 >= 16 && b1 <= 31)) {
						return false;
					}
					if (b0 == 192 && b1 == 168) {
						return false;
					}
					if (b0 == 100 && (b1 >= 64 && b1 <= 127)) {
						return false;
					}
					if (b0 == 198 && (b1 == 18 || b1 == 19)) {
						return false;
					}
					if ((b0 & 0xF0) == 240) {
						return false;
					}
				} else if (bytes.length == 16) {
					boolean isIPv4Mapped = true;
					for (int i = 0; i < 10; i++) {
						if (bytes[i] != 0) {
							isIPv4Mapped = false;
							break;
						}
					}
					if (isIPv4Mapped && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
						int b0 = bytes[12] & 0xFF;
						int b1 = bytes[13] & 0xFF;
						if (b0 == 0 || b0 == 10 || b0 == 127 || (b0 == 169 && b1 == 254)
								|| (b0 == 172 && (b1 >= 16 && b1 <= 31)) || (b0 == 192 && b1 == 168)
								|| ((b0 & 0xF0) == 240)) {
							return false;
						}
					}
				}
			}
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public boolean send(Message event) {

		// throttle to 5 requests per second
		if (lastExternalRequest > System.currentTimeMillis() - 500) {
			return false;
		}
		lastExternalRequest = System.currentTimeMillis();

		// extract message data
		final String url;
		if (event.data.containsKey("url")) {
			String temp = ((String) event.data.get("url")).trim();

			// check for protocol, add if missing
			if (!temp.startsWith("http")) {
				temp = "https://" + temp;
			}

			url = temp;
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

		final String channel;
		if (event.data.containsKey("channel")) {
			channel = ((String) event.data.get("channel")).trim();
		} else {
			channel = event.getSender();
		}

		// abort if key data is missing or URL fails safety checks
		if (url.isEmpty() || !isSafeUrl(url)) {
			return false;
		}

		// log and make the call
		logger.info("Calling http-web-request for URL " + url + " with method " + method + " for " + channel + " by "
				+ event.getSender());
		try {
			WSRequest request = wsClient.url(url).setRequestTimeout(Duration.ofSeconds(5));
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
				if (validate(event.getRecipient())) {
					Message m = new Message("http-web-request", channel);
					m.data.putAll(event.data);
					m.data.put("result-status", response.getStatus());
					String body = response.getBody();
					if (body != null && body.length() > MAX_RESPONSE_BODY_LENGTH) {
						body = body.substring(0, MAX_RESPONSE_BODY_LENGTH);
					}
					m.data.put("result-body", body);
					m.data.put("result-content-type", response.getContentType());
					if (event.data.containsKey(OOCSICall.MESSAGE_ID)) {
						m.data.put(OOCSICall.MESSAGE_ID, event.data.get(OOCSICall.MESSAGE_ID));
					}

					Channel c = server.getChannel(channel);
					if (c != null) {
						c.send(m);

						// log access
						OOCSIServer.logEvent(token, "", channel, event.data, event.getTimestamp());
					}
				}
			}).exceptionally(e -> {
				logger.error("Problem calling http-web-request for URL " + url + " with method " + method + " for "
						+ channel + " by " + event.getSender() + ": " + e.getLocalizedMessage());
				return null;
			});

			return true;
		} catch (Exception e) {
			logger.error("Problem calling http-web-request for URL " + url + " with method " + method + " for "
					+ channel + " by " + event.getSender() + ": " + e.getLocalizedMessage());
			if (!validate(event.getRecipient())) {
				return false;
			}

			Message m = new Message("http-web-request", channel);
			m.data.putAll(event.data);
			m.data.put("result-status", 404);
			m.data.put("result-body", "The URL seems to be malformed.");
			m.data.put("result-content-type", "");

			Channel c = server.getChannel(channel);
			if (c != null) {
				c.send(m);

				// log access
				OOCSIServer.logEvent(token, "", channel, event.data, event.getTimestamp());
			}

			return true;
		}
	}
}
