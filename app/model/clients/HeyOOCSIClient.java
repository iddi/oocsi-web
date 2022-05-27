package model.clients;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;

import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;
import play.libs.Json;

@Singleton
public class HeyOOCSIClient extends Client {

	final private OOCSIServer server;
	private Map<String, OOCSIDevice> clients;

	@Inject
	public HeyOOCSIClient(OOCSIServer server) {
		super("heyOOCSIClient", server.getChangeListener());

		this.server = server;
		this.clients = new HashMap<>();
		server.addClient(this);
		server.subscribe(this, "heyOOCSI!");
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

	/**
	 * parse the heyOOCSI! message and extract devices and their components
	 *
	 */
	@Override
	public void send(Message event) {
		// read and parse heyOOCSI message
		if (event.getRecipient().equals("heyOOCSI!")) {
			synchronized (clients) {
				event.data.forEach((k, v) -> {
					if (v instanceof ObjectNode) {
						ObjectNode on = ((ObjectNode) v);

						// seems we have an object, let's parse it
						OOCSIDevice od = new OOCSIDevice();
						od.name = k;
						od.deviceId = on.at("/properties/device_id").asText("");
						on.at("/components").fields().forEachRemaining(e -> {
							DeviceEntity dc = new DeviceEntity();
							dc.name = e.getKey();
							JsonNode value = e.getValue();
							dc.channel = value.get("channel_name").asText("");
							dc.type = value.get("type").asText("");
							dc.icon = value.get("icon").asText("");
							// retrieve default value or state which are mutually exclusive
							if (value.has("value")) {
								dc.value = value.get("value").asText();
							} else if (value.has("state")) {
								dc.value = value.get("state").asText();
							}
							od.components.add(dc);
						});
						on.at("/location").fields().forEachRemaining(e -> {
							if (e.getValue().isArray()) {
								Float[] locationComponents = new Float[2];
								locationComponents[0] = new Float(((ArrayNode) e.getValue()).get(0).asDouble());
								locationComponents[1] = new Float(((ArrayNode) e.getValue()).get(1).asDouble());
								od.locations.put(e.getKey(), locationComponents);
							}
						});
						on.at("/properties").fields().forEachRemaining(e -> {
							od.properties.put(e.getKey(), e.getValue().asText());
						});

						// check contents of device

						// then add to clients
						clients.put(k, od);
					}
				});
			}
		} else {
			if (event.data.containsKey("clientHandle")) {
				String clientHandle = (String) event.data.get("clientHandle");
				if (clients.containsKey(clientHandle)) {
					OOCSIDevice od = clients.get(clientHandle);
					Client c = server.getClient(event.getSender());
					if (c != null) {
						c.send(new Message(token, event.getSender()).addData("clientHandle", clientHandle)
						        .addData("location", od.serializeLocations())
						        .addData("components", od.serializeComponents())
						        .addData("properties", od.serializeProperties()));
					}
				}
			} else if (event.data.containsKey("x") && event.data.containsKey("y")
			        && event.data.containsKey("distance")) {
				try {
					final float x = ((Number) event.data.get("x")).floatValue();
					final float y = ((Number) event.data.get("y")).floatValue();
					final float distance = ((Number) event.data.get("distance")).floatValue();

					// check if we need to truncate the client list, according to the "n closest clients"
					final int closest;
					if (event.data.containsKey("closest")) {
						closest = ((Number) event.data.get("closest")).intValue();
					} else {
						closest = 100;
					}

					// build list of all client within given distance
					Map<Double, String> clientNames = new HashMap<>();
					clients.values().stream().forEach(od -> {
						od.locations.entrySet().forEach(loc -> {
							Float[] location = loc.getValue();
							double dist = Math.hypot(Math.abs(location[0] - x), Math.abs(location[1] - y));
							if (dist < distance) {
								clientNames.put(dist, od.deviceId);
							}
						});
					});

					// create sorted list of clients, potentially truncated by "closest"
					List<String> cns = clientNames.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(closest)
					        .map(e -> e.getValue()).collect(Collectors.toList());

					// assemble the clients within distance from reference point and send back
					Client c = server.getClient(event.getSender());
					if (c != null) {
						c.send(new Message(token, event.getSender()).addData("x", x).addData("y", y)
						        .addData("distance", distance).addData("clients", cns.toArray(new String[] {})));
					}
				} catch (NumberFormatException | ClassCastException e) {
					// could not parse the coordinates or distance, do nothing
				}
			} else if (event.data.containsKey("location")) {
				String location = ((String) event.data.get("location")).trim();
				Set<String> clientNames = new HashSet<>();
				clients.values().stream().forEach(od -> {
					od.locations.keySet().forEach(loc -> {
						if (loc.equalsIgnoreCase(location)) {
							clientNames.add(od.deviceId);
						}
					});
				});

				// assemble the clients within given location and send back
				Client c = server.getClient(event.getSender());
				if (c != null) {
					c.send(new Message(token, event.getSender()).addData("location", location).addData("clients",
					        clientNames.toArray(new String[] {})));
				}
			}
		}
	}

	/**
	 * remove devices that are not in the current server client list anymore
	 * 
	 */
	private void purgeStaleClients() {
		synchronized (clients) {
			clients = clients.values().stream()
			        .filter(od -> this.server.getClients().stream().anyMatch(c -> c.getName().equals(od.deviceId))
			                || !od.purgeable())
			        .collect(Collectors.toMap(od -> od.name, od -> od));
		}
	}

	/**
	 * retrieve devices mapped by location
	 * 
	 * @return
	 */
	public Multimap<String, OOCSIDevice> devicesByLocation() {
		purgeStaleClients();

		Multimap<String, OOCSIDevice> locationsMappedDevices = MultimapBuilder.hashKeys().linkedListValues().build();
		clients.values().stream().forEach(
		        od -> od.locations.entrySet().stream().forEach(loc -> locationsMappedDevices.put(loc.getKey(), od)));
		return locationsMappedDevices;
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	public String labelLocation(String location) {
		return "📍 " + location;
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	public static class OOCSIDevice {
		public String name;
		public String deviceId;
		public Map<String, Float[]> locations = new HashMap<>();
		public List<DeviceEntity> components = new LinkedList<>();
		public Map<String, String> properties = new HashMap<>();
		public String icon;
		private long purgeTimestamp = -1;

		public boolean purgeable() {
			if (purgeTimestamp == -1) {
				purgeTimestamp = System.currentTimeMillis();
			}
			return System.currentTimeMillis() - purgeTimestamp > 3600 * 1000;
		}

		@Override
		public String toString() {
			return "📦 " + name;
		}

		public ObjectNode serializeLocations() {
			ObjectNode locs = Json.newObject();
			locations.entrySet().stream().forEach(l -> {
				ArrayNode an = locs.putArray(l.getKey());
				an.add(l.getValue()[0]);
				an.add(l.getValue()[1]);
			});
			return locs;
		}

		public ObjectNode serializeComponents() {
			ObjectNode locs = Json.newObject();
			components.stream().forEach(de -> {
				ObjectNode on = locs.putObject(de.name);
				on.put("channel_name", de.channel);
				on.put("default_value", de.value);
				on.put("type", de.type);
				on.put("icon", de.icon);
			});
			return locs;
		}

		public ObjectNode serializeProperties() {
			ObjectNode props = Json.newObject();
			properties.entrySet().stream().forEach(de -> {
				props.put(de.getKey(), de.getValue());
			});
			return props;
		}
	}

	public static class DeviceEntity {
		public String name;
		public String channel;
		public String value;
		public String type;
		public String icon;

		@Override
		public String toString() {
			switch (type) {
			case "switch":
				return "🎚 " + name;
			case "number":
				return "🧮 " + name;
			case "sensor":
			case "binary_sensor":
				return "🩺 " + name;
			case "light":
				return "💡 " + name;
			default:
				return name;
			}
		}
	}
}
