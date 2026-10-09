package model.clients;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.tue.id.oocsi.client.services.OOCSICall;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.model.Server;
import nl.tue.id.oocsi.server.protocol.Message;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;

public class HTTPRequestClient extends Client {

	private static final int MAX_RESPONSE_BODY_LENGTH = 65536;
	private static final int MAX_REDIRECT_HOPS = 3;

	private static final Logger logger = LoggerFactory.getLogger(HTTPRequestClient.class);

	private Server server;
	private WSClient wsClient;
	private boolean followRedirects = false;
	private long lastExternalRequest = System.currentTimeMillis();

	public HTTPRequestClient(String token, Server server, WSClient wsClient) {
		this(token, server, wsClient, false);
	}

	public HTTPRequestClient(String token, Server server, WSClient wsClient, boolean followRedirects) {
		super(token, server != null ? server.getChangeListener() : null);

		this.server = server;
		this.wsClient = wsClient;
		this.followRedirects = followRedirects;
		if (server != null) {
			server.addClient(this);
		}
	}

	public boolean isFollowRedirects() {
		return followRedirects;
	}

	public void setFollowRedirects(boolean followRedirects) {
		this.followRedirects = followRedirects;
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
					|| host.endsWith(".internal") || host.endsWith(".arpa") || host.endsWith(".onion")) {
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
					if (!isSafeIpv4(bytes, 0)) {
						return false;
					}
				} else if (bytes.length == 16) {
					int b0 = bytes[0] & 0xFF;
					int b1 = bytes[1] & 0xFF;

					// IPv6 ULA (fc00::/7) - RFC 4193
					if ((b0 & 0xFE) == 0xFC) {
						return false;
					}
					// IPv6 Link-Local (fe80::/10) - RFC 4291
					if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
						return false;
					}
					// IPv6 Site-Local deprecated (fec0::/10)
					if (b0 == 0xFE && (b1 & 0xC0) == 0xC0) {
						return false;
					}
					// IPv6 Multicast (ff00::/8)
					if (b0 == 0xFF) {
						return false;
					}
					// IPv6 Documentation prefix (2001:db8::/32) - RFC 3849
					if (b0 == 0x20 && b1 == 0x01 && (bytes[2] & 0xFF) == 0x0D && (bytes[3] & 0xFF) == 0xB8) {
						return false;
					}
					// Discard prefix (100::/64) - RFC 6666
					if (b0 == 0x01 && b1 == 0x00 && bytes[2] == 0 && bytes[3] == 0 && bytes[4] == 0 && bytes[5] == 0
							&& bytes[6] == 0 && bytes[7] == 0) {
						return false;
					}
					// 6to4 prefix (2002::/16) - RFC 3056 (contains embedded IPv4 in bytes 2..5)
					if (b0 == 0x20 && b1 == 0x02) {
						if (!isSafeIpv4(bytes, 2)) {
							return false;
						}
					}
					// IPv6-mapped IPv4 (::ffff:0:0/96)
					boolean isPrefixAllZeros = true;
					for (int i = 0; i < 10; i++) {
						if (bytes[i] != 0) {
							isPrefixAllZeros = false;
							break;
						}
					}
					if (isPrefixAllZeros && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
						if (!isSafeIpv4(bytes, 12)) {
							return false;
						}
					}
					// IPv6 IPv4-compatible (::/96)
					boolean isCompatiblePrefix = true;
					for (int i = 0; i < 12; i++) {
						if (bytes[i] != 0) {
							isCompatiblePrefix = false;
							break;
						}
					}
					if (isCompatiblePrefix) {
						if (!isSafeIpv4(bytes, 12)) {
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

	private static boolean isSafeIpv4(byte[] bytes, int offset) {
		int b0 = bytes[offset] & 0xFF;
		int b1 = bytes[offset + 1] & 0xFF;
		int b2 = bytes[offset + 2] & 0xFF;

		// 0.0.0.0/8 (current network)
		// 10.0.0.0/8 (RFC 1918)
		// 127.0.0.0/8 (loopback)
		if (b0 == 0 || b0 == 10 || b0 == 127) {
			return false;
		}
		// 169.254.0.0/16 (link local / metadata)
		if (b0 == 169 && b1 == 254) {
			return false;
		}
		// 172.16.0.0/12 (RFC 1918)
		if (b0 == 172 && (b1 >= 16 && b1 <= 31)) {
			return false;
		}
		// 192.168.0.0/16 (RFC 1918)
		if (b0 == 192 && b1 == 168) {
			return false;
		}
		// 100.64.0.0/10 (Carrier grade NAT)
		if (b0 == 100 && (b1 >= 64 && b1 <= 127)) {
			return false;
		}
		// 198.18.0.0/15 (Benchmarking)
		if (b0 == 198 && (b1 == 18 || b1 == 19)) {
			return false;
		}
		// 240.0.0.0/4 (Class E / reserved / broadcast)
		if ((b0 & 0xF0) == 240) {
			return false;
		}
		// 192.0.0.0/24 (IETF protocol assignments)
		if (b0 == 192 && b1 == 0 && b2 == 0) {
			return false;
		}
		// 192.0.2.0/24 (TEST-NET-1)
		if (b0 == 192 && b1 == 0 && b2 == 2) {
			return false;
		}
		// 198.51.100.0/24 (TEST-NET-2)
		if (b0 == 198 && b1 == 51 && b2 == 100) {
			return false;
		}
		// 203.0.113.0/24 (TEST-NET-3)
		if (b0 == 203 && b1 == 0 && b2 == 113) {
			return false;
		}
		// 192.88.99.0/24 (6to4 relay anycast)
		if (b0 == 192 && b1 == 88 && b2 == 99) {
			return false;
		}

		return true;
	}

	private CompletionStage<WSResponse> executeRequest(String currentUrl, String method, String postBody,
			String postJson, int hopCount) {
		WSRequest request = wsClient.url(currentUrl).setFollowRedirects(false).setRequestTimeout(Duration.ofSeconds(5));
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

		if (!followRedirects) {
			return wsResponse;
		}

		return wsResponse.thenCompose(response -> {
			int status = response.getStatus();
			if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
				Optional<String> locationOpt = response.getSingleHeader("Location");
				if (locationOpt.isPresent() && hopCount < MAX_REDIRECT_HOPS) {
					String location = locationOpt.get().trim();
					try {
						URI currentUri = new URI(currentUrl);
						URI targetUri = currentUri.resolve(location);
						String targetUrl = targetUri.toString();
						if (isSafeUrl(targetUrl)) {
							String redirectMethod = (status == 307 || status == 308) ? method : "get";
							return executeRequest(targetUrl, redirectMethod, postBody, postJson, hopCount + 1);
						} else {
							logger.warn("Blocked redirect to unsafe URL: " + targetUrl);
							return CompletableFuture.completedFuture(response);
						}
					} catch (Exception e) {
						logger.warn("Malformed redirect URL: " + location);
						return CompletableFuture.completedFuture(response);
					}
				}
			}
			return CompletableFuture.completedFuture(response);
		});
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
			executeRequest(url, method, postBody, postJson, 0).thenAccept(response -> {
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
