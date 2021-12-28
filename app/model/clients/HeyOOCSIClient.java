package model.clients;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;

import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;

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
		if (event.recipient.equals("heyOOCSI!")) {
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
							dc.channel = e.getValue().get("channel_name").asText();
							dc.type = e.getValue().get("type").asText();
							od.components.add(dc);
						});
						on.at("/location").fields().forEachRemaining(e -> {
							od.locations.add(e.getKey());
						});

						// check contents of device

						// then add to clients
						clients.put(k, od);
					}
				});
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
		clients.values().stream()
		        .forEach(od -> od.locations.stream().forEach(loc -> locationsMappedDevices.put(loc, od)));
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
		public List<String> locations = new LinkedList<>();
		public List<DeviceEntity> components = new LinkedList<>();
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
	}

	public static class DeviceEntity {
		public String name;
		public String channel;
		public String type;

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
