package controllers;

import static akka.pattern.Patterns.ask;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.typesafe.config.Config;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.stream.Materializer;
import model.actors.ServiceClientActor;
import model.actors.WebSocketClientActor;
import model.clients.HeyOOCSIClient;
import model.clients.HeyOOCSIClient.DeviceEntity;
import model.clients.HeyOOCSIClient.OOCSIDevice;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.model.Client;
import nl.tue.id.oocsi.server.protocol.Message;
import play.Environment;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.inject.ApplicationLifecycle;
import play.libs.Json;
import play.libs.streams.ActorFlow;
import play.mvc.Controller;
import play.mvc.Http.Cookie;
import play.mvc.Http.Cookie.SameSite;
import play.mvc.Http.Request;
import play.mvc.Result;
import play.mvc.WebSocket;
import scala.compat.java8.FutureConverters;
import scala.concurrent.ExecutionContext;
import utils.SummarizingLogger;

@Singleton
public class Application extends Controller {

	private final ActorSystem system;
	private final Materializer materializer;
	private final FormFactory formFactory;
	private final OOCSIServer server;
	private final HeyOOCSIClient heyOOCSIClient;

	private static final Logger logger = LoggerFactory.getLogger(Application.class);

	@Inject
	public Application(ActorSystem as, Materializer m, ApplicationLifecycle lifecycle, ExecutionContext ec,
	        FormFactory f, Environment env, OOCSIServer server, HeyOOCSIClient heyOOCSIClient, Config configuration,
	        SummarizingLogger sl) {

		this.system = as;
		this.materializer = m;
		this.formFactory = f;
		this.server = server;
		this.heyOOCSIClient = heyOOCSIClient;

		// configure the number of maximal clients
		if (configuration.hasPath("oocsi.clients")) {
			try {
				int clients = configuration.getInt("oocsi.clients");
				OOCSIServer.getInstance().setMaxClients(clients);
			} catch (Exception e) {
				logger.warn("Property 'oocsi.clients' not readable or parseable in configuration");
			}
		}

		// trigger the log summary every minute
		as.scheduler().scheduleAtFixedRate(Duration.ofMinutes(1), Duration.ofMinutes(1), () -> {
			sl.logSummary();
		}, ec);

		// register shutdown hook
		lifecycle.addStopHook(() -> {
			server.stop();
			Thread.sleep(1000);
			return CompletableFuture.completedFuture(null);
		});
	}

	/**
	 * action to show the landing page for the OOCSI server
	 * 
	 * @return
	 */
	public Result index(Request request) {
		String channels = server.getChannelList().replace("OOCSI_connections,", "").replace("OOCSI_clients,", "")
		        .replace("OOCSI_events,", "").replace("OOCSI_metrics,", "").replace("OSC,", "");
		if (channels.length() > 160) {
			channels = channels.substring(0, 160) + "...";
		}

		String clients = server.getClientList();
		if (clients.length() > 160) {
			clients = clients.substring(0, 160) + "...";
		}

		return ok(views.html.Application.index.render("index", "", request.host(), clients, channels));
	}

	/**
	 * action to show server metrics page
	 * 
	 * @return
	 */
	public Result metrics(Request request) {
		return ok(views.html.Application.metrics.render("metrics", "", request.host()));
	}

	/**
	 * action to show server network page
	 * 
	 * @return
	 */
	public Result network(Request request) {
		return ok(views.html.Application.network.render("network", "", request.host()));
	}

	/**
	 * retrieve the network of OOCSI connected clients and channels
	 * 
	 * @return
	 */
	public Result netJson() {

		ObjectNode on = Json.newObject();
		ArrayNode nodes = on.putArray("nodes");
		ArrayNode links = on.putArray("links");

		// add root node (server)
		ObjectNode serverNode = Json.newObject();
		serverNode.put("id", "OOCSI Server");
		serverNode.put("value", 4);
		serverNode.put("group", 0);
		nodes.add(serverNode);

		for (Channel c : server.getChannels()) {
			if (!c.isPrivate() && !c.getName().startsWith("OOCSI_")) {
				ObjectNode node = Json.newObject();
				node.put("id", c.getName());
				node.put("group", c instanceof Client ? 2 : 1);
				node.put("value", c.getChannels().size());
				nodes.add(node);

				ObjectNode link = Json.newObject();
				link.put("source", "OOCSI Server");
				link.put("target", c.getName());
				link.put("value", (c instanceof Client ? 5 : 1) + c.getChannels().size());
				links.add(link);

				for (Channel c1 : c.getChannels()) {
					ObjectNode link1 = Json.newObject();
					link1.put("source", c.getName());
					link1.put("target", c1.getName());
					link1.put("value", 1 + c1.getChannels().size());
					links.add(link1);
				}
			}
		}

		{
			// add hey OOCSI locations
			Set<String> completedDevices = new HashSet<>();
			Multimap<String, OOCSIDevice> locations = heyOOCSIClient.devicesByLocation();
			locations.keySet().stream().forEach(k -> {

				{
					ObjectNode locationNode = Json.newObject();
					locationNode.put("id", k);
					locationNode.put("label", heyOOCSIClient.labelLocation(k));
					locationNode.put("value", 3);
					locationNode.put("group", 3);
					nodes.add(locationNode);

					ObjectNode link = Json.newObject();
					link.put("source", "OOCSI Server");
					link.put("target", k);
					links.add(link);
				}

				Collection<OOCSIDevice> locationDevices = locations.get(k);
				for (OOCSIDevice od : locationDevices) {

					// add location link to device
					String deviceName = "[ " + od.name + " ]";
					{
						ObjectNode link = Json.newObject();
						link.put("source", k);
						link.put("target", deviceName);
						links.add(link);
					}

					// only add device once, even though it might be present in multiple locations
					if (completedDevices.contains(od.name)) {
						continue;
					}
					completedDevices.add(od.name);

					{
						ObjectNode deviceNode = Json.newObject();
						deviceNode.put("id", deviceName);
						deviceNode.put("label", od.toString());
						deviceNode.put("value", 1);
						deviceNode.put("group", 4);
						nodes.add(deviceNode);
					}
					{
						ObjectNode link = Json.newObject();
						link.put("source", od.deviceId);
						link.put("target", deviceName);
						links.add(link);
					}

					// add OOCSI entities for this location
					for (DeviceEntity dc : od.components) {
						ObjectNode entityNode = Json.newObject();
						entityNode.put("id", od.name + "_" + dc.name);
						entityNode.put("label", dc.toString());
						entityNode.put("value", 2);
						entityNode.put("group", 5);
						nodes.add(entityNode);

						ObjectNode link = Json.newObject();
						link.put("source", deviceName);
						link.put("target", od.name + "_" + dc.name);
						links.add(link);
					}
				}
			});
		}

		return ok(on).as("application/json");
	}

	/**
	 * websocket connections end up here
	 * 
	 * @return
	 */
	public WebSocket ws() {
		return WebSocket.Text.accept(
		        request -> ActorFlow.actorRef(out -> WebSocketClientActor.props(out, server), system, materializer));
	}

	/**
	 * return list of channels for GET request
	 * 
	 * @return
	 */
	public Result channels() {
		return ok(server.getChannelList());
	}

	// ----------------------------------------------------------------------------------------------------------------

	/**
	 * handle GET request to send a message to a channel
	 * 
	 * @param channel
	 * @param data
	 * @return
	 */
	public Result sendData(Request request, String channel, String data) {

		// extract user id if provided
		String userId = extractUserId(request);

		if (channel == null || channel.trim().length() == 0) {
			return badRequest("ERROR: channel missing");
		} else if (server.getChannel(channel) == null) {
			return notFound("ERROR: channel not found");
		} else {
			Message m = new Message("WEB-REQUEST", channel);
			m.addData("parameter", data);
			m.addData("userId", userId);
			server.getChannel(channel).send(m);

			return ok("").withCookies(createUserIdCookie(request, userId));
		}
	}

	/**
	 * handle GET request to send a message to a channel and auto-close the window or tab, even if there is an error
	 * message (bad request, not found)
	 * 
	 * @param channel
	 * @param data
	 * @return
	 */
	public Result sendDataAndClose(Request request, String channel, String data) {

		// extract user id if provided
		String userId = extractUserId(request);

		if (channel == null || channel.trim().length() == 0) {
			return badRequest(views.html.Application.sendAndClose.render());
		} else if (server.getChannel(channel) == null) {
			return notFound(views.html.Application.sendAndClose.render());
		} else {
			Message m = new Message("WEB-REQUEST", channel);
			m.addData("parameter", data);
			m.addData("userId", userId);
			server.getChannel(channel).send(m);

			return ok(views.html.Application.sendAndClose.render()).withCookies(createUserIdCookie(request, userId));
		}
	}

	/**
	 * track GET request to send a message to a channel
	 * 
	 * @param channel
	 * @param data
	 * @return
	 */
	public Result track(Request request, String channel, String data) {

		// extract user id if provided
		String userId = extractUserId(request);

		if (channel == null || channel.trim().length() == 0) {
			return badRequest("ERROR: channel missing");
		} else if (server.getChannel(channel) == null) {
			return notFound("ERROR: channel not found");
		} else {
			Message m = new Message("WEB-REQUEST", channel);
			m.addData("parameter", data);
			m.addData("User-Agent", request.header("User-Agent").orElse(""));
			m.addData("Referer", request.header("Referer").orElse(""));
			m.addData("Origin", request.header("Origin").orElse(""));
			m.addData("userId", userId);

			server.getChannel(channel).send(m);

			return ok("").withCookies(createUserIdCookie(request, userId));
		}
	}

	/**
	 * handle POST request to send a message to a channel
	 * 
	 * @param channel
	 * @return
	 */
	public Result send(Request request, String channel) {

		// extract user id if provided
		String userId = extractUserId(request);

		// // check channel available
		if (channel == null || channel.trim().isEmpty()) {
			return badRequest("ERROR: channel missing");
		}

		String sender;
		JsonNode jn = request.body().asJson();
		if (jn != null) {
			JsonNode sjn = jn.get("sender");
			if (sjn != null) {
				sender = sjn.asText();
			} else {
				return badRequest("ERROR: sender missing");
			}

			// convert to Map
			Map<String, String> map = new HashMap<>();
			jn.fields().forEachRemaining(i -> {
				map.put(i.getKey(), i.getValue().asText());
			});

			return internalSend(sender, channel, userId, map).withCookies(createUserIdCookie(request, userId));
		} else {
			DynamicForm dynamicForm = formFactory.form().bindFromRequest(request);

			// check server available
			sender = dynamicForm.get("sender");
			if (sender == null || sender.trim().isEmpty()) {
				return badRequest("ERROR: sender missing");
			}

			return internalSend(sender, channel, userId, dynamicForm.rawData())
			        .withCookies(createUserIdCookie(request, userId));
		}

	}

	/**
	 * internal handling of web send request
	 * 
	 * @param sender
	 * @param channel
	 * @param messageData
	 * @return
	 */
	private Result internalSend(String sender, String channel, String userId, Map<String, String> messageData) {
		// check whether there is another client with same name (-> abort)
		Client serverClient = server.getClient(sender);
		if (serverClient != null) {
			return unauthorized("ERROR: different client with same name exists");
		}

		// check whether recipient channel is active
		Channel serverChannel = server.getChannel(channel);
		if (serverChannel != null) {
			// create message
			Message message = new Message(sender, channel);

			// fill message
			for (String key : messageData.keySet()) {
				if (!key.equals("sender") || !key.equals("channel") || !key.equals("recipient")
				        || !key.equals("timestamp") || !key.equals("")) {
					message.addData(key, messageData.get(key));
				}
			}
			message.addData("userId", userId);

			// send message
			serverChannel.send(message);
		} else {
			return notFound("ERROR: channel not registered");
		}

		return ok("Message delivered to " + channel);
	}

	/**
	 * extract the value of a userId cookie set on the given request, if not present create a new userId based on UUID
	 * 
	 * @param request
	 * @return
	 */
	private String extractUserId(Request request) {
		return request.getCookie("userId").map(c -> c.value().toString()).orElse(UUID.randomUUID().toString());
	}

	/**
	 * creates a cookie for the given userId based on the request
	 * 
	 * @param request
	 * @param userId
	 * @return
	 */
	private Cookie createUserIdCookie(Request request, String userId) {
		return new Cookie("userId", userId, 24 * 3600, "/",
		        (request.host().startsWith("localhost") ? null : request.host()), request.secure(), true, SameSite.LAX);
	}

	// ----------------------------------------------------------------------------------------------------------------

	/**
	 * respond to service check
	 * 
	 * @param service
	 * @return
	 */
	public CompletionStage<Result> serviceIndex(String service) {
		if (server.getChannel(service) != null) {
			return CompletableFuture.completedFuture(ok(service + " ok"));
		} else {
			return CompletableFuture.completedFuture(notFound(service + " not found"));
		}
	}

	/**
	 * respond to GET request to service (forward, return)
	 * 
	 * @param service
	 * @param call
	 * @param data
	 * @return
	 */
	public CompletionStage<Result> serviceCall(String service, String call, String data) {
		if (server.getChannel(service) != null) {
			return internalServiceCall(service, call, data);
		} else {
			return CompletableFuture.completedFuture(notFound(service + " not found"));
		}
	}

	/**
	 * respond to POST request to service (forward, return)
	 * 
	 * @param service
	 * @param call
	 * @return
	 */
	public CompletionStage<Result> serviceCallPost(Request request, String service, String call) {
		if (server.getChannel(service) != null) {
			return internalServiceCall(service, call, request.body().asText());
		} else {
			return CompletableFuture.completedFuture(notFound(service + " not found"));
		}
	}

	/**
	 * internal dispatch for the incoming calls
	 * 
	 * @param service
	 * @param call
	 * @param data
	 * @return
	 */
	private CompletionStage<Result> internalServiceCall(String service, String call, String data) {
		final ActorRef a = system.actorOf(ServiceClientActor.props(server));
		CompletionStage<Result> prom = FutureConverters
		        .toJava(ask(a, new ServiceClientActor.ServiceRequest(service, call, data), 5000))
		        .thenApply(response -> {

			        // kill actor
			        a.tell(PoisonPill.getInstance(), a);

			        if (response == null) {
				        return noContent();
			        } else {
				        Map<String, Object> messageData = ((Message) response).data;
				        if (messageData.containsKey("html"))
					        return ok((String) messageData.get("html")).as("text/html")
					                .withHeader("Access-Control-Allow-Origin", "*")
					                .withHeader("Access-Control-Allow-Headers", "X-Requested-With")
					                .withHeader("Access-Control-Allow-Headers", "Content-Type")
					                .withHeader("Access-Control-Allow-Methods", "PUT, GET, POST, DELETE, OPTIONS");
				        else if (messageData.containsKey("text"))
					        return ok((String) messageData.get("text")).as("text/plain");
				        else if (messageData.containsKey("json"))
					        return ok((String) messageData.get("json")).as("text/json");
				        else if (messageData.containsKey("csv"))
					        return ok((String) messageData.get("csv")).as("text/csv");
				        else
					        return ok();
			        }
		        }).exceptionally(e -> {

			        // kill actor
			        a.tell(PoisonPill.getInstance(), a);

			        return notFound(service + " not found");
		        });
		return prom;
	}
}
