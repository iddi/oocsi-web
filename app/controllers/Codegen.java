package controllers;

import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import model.codegen.Interactable;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http.Request;
import play.mvc.Result;

public class Codegen extends Controller {

	// @BodyParser.Of(BodyParser.Json.class)
	public Result generateOOCSIMoteCode(Request request) {

		// Map<String, String[]> items = request().body().asFormUrlEncoded();
		JsonNode jn = request.body().asJson();
		if (jn == null || !jn.isObject()) {
			return badRequest();
		}

		ObjectNode on = (ObjectNode) jn;

		// check controls
		if (!on.has("controls") || !on.get("controls").isArray()) {
			return badRequest();
		}

		// get configuration aspects
		List<Interactable> controls = new LinkedList<Interactable>();
		String server = on.get("server").asText("SERVER ADDRESS");
		server = server.replaceAll("[:][0-9]+", "");
		String channel = on.get("channel").asText("testchannel");
		String platform = on.get("platform").asText("ESP32");
		ArrayNode an = (ArrayNode) on.get("controls");
		for (JsonNode jsonNode : an) {
			Interactable i = Json.fromJson(jsonNode, Interactable.class);

			if (i.type != null) {
				controls.add(i);
			}
		}

		if (platform.equals("ESP32")) {
			return ok(views.html.Codegen.moteCode_ESP32.render(server, channel, controls));
			// } else if (platform.equals("ESP8266")) {
			// return ok(views.html.Codegen.moteCode_ESP32.render(channel, controls)).as("text/application/code");
		} else if (platform.equals("Processing")) {
			return ok(views.html.Codegen.moteCode_Processing.render(server, channel, controls));
			// } else if (platform.equals("Python")) {
			// return ok(views.html.Codegen.moteCode_ESP32.render(channel, controls)).as("text/application/code");
			// } else if (platform.equals("JS")) {
			// return ok(views.html.Codegen.moteCode_ESP32.render(channel, controls)).as("text/application/code");
		} else {
			return badRequest();
		}
	}

}
