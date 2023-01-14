package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Multimap;
import com.google.inject.Inject;

import model.clients.HeyOOCSIClient;
import model.clients.HeyOOCSIClient.OOCSIDevice;
import nl.tue.id.oocsi.server.OOCSIServer;
import play.Environment;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http.Request;
import play.mvc.Result;

public class Tools extends Controller {

	private final Environment environment;
	private final OOCSIServer oocsiServer;
	private final HeyOOCSIClient heyOOCSIClient;

	@Inject
	public Tools(Environment env, OOCSIServer oocsi, HeyOOCSIClient heyOOCSIClient) {
		this.environment = env;
		this.oocsiServer = oocsi;
		this.heyOOCSIClient = heyOOCSIClient;
	}

	/**
	 * action to show an overview page for all tools and testing facilities
	 * 
	 * @return
	 */
	public Result index(Request request) {
		return ok(views.html.Tools.index.render("Tools", "", request.host()));
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * action to show a page to inspect all values sent over selected channels
	 * 
	 * @return
	 */
	public Result datatiles(Request request) {
		return ok(views.html.Tools.datatiles.render("datatiles", "", request.host()));
	}

	/**
	 * action to show a page to make your own charts based on selected data from channels
	 * 
	 * @return
	 */
	public Result dashboard(Request request) {
		return ok(views.html.Tools.dashboard.render("dashboard", "", request.host()));
	}

	/**
	 * action to show OOCSI mote page
	 * 
	 * @return
	 */
	public Result mote(Request request) {
		return ok(views.html.Tools.mote.render("OOCSImote", "", request.host()));
	}

	/**
	 * action to show OOCSI mote sharing page
	 * 
	 * @return
	 */
	public Result moteShare(Request request) {
		return ok(views.html.Tools.moteShare.render("OOCSImote", "", request.host()));
	}

	/**
	 * action to show OOCSI animation page
	 * 
	 * @return
	 */
	public Result animate(Request request) {
		return ok(views.html.Tools.animate.render("animOOCSI", "", request.host()));
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * action to show the data canvas page
	 * 
	 * @return
	 */
	public Result datacanvas(Request request, String token) {
		if (token == null || token.isEmpty() || !token.matches("^[A-Za-z0-9-_]+$")) {
			return ok(views.html.Tools.token.render("Please enter the channel token:", "Data Canvas"));
		} else {
			return ok(views.html.Tools.datacanvas.render("Data Canvas", token));
		}
	}

	/**
	 * show a visualization of the current map of heyOOCSI-identified devices
	 * 
	 * @param request
	 * @return
	 */
	public Result heyOOCSI(Request request, String route) {
		Multimap<String, OOCSIDevice> locations = heyOOCSIClient.devicesByLocation();
		return ok(views.html.Tools.heyOOCSI.render(locations.asMap()));
	}

	/**
	 * action to show the IoTsim page
	 * 
	 * @return
	 */
	public Result iotsim(Request request) {
		return ok(views.html.Tools.iotsim.render("IoTsim", "", request.host()));
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * action to show a test page for websocket experiments with OOCSI
	 * 
	 * @return
	 */
	public Result testJotted(Request request) {
		return ok(views.html.Test.testJotted.render("Testing", "", request.host(), environment.isProd()));
	}

	/**
	 * action to show a test page for websocket experiments with OOCSI
	 * 
	 * @return
	 */
	public Result testRaw(Request request) {
		return ok(views.html.Test.testRaw.render("Testing", "", request.host()));
	}

	/**
	 * action to show a test page for websocket experiments with OOCSI
	 * 
	 * @return
	 */
	public Result testVisual(Request request) {
		return ok(views.html.Test.testVisual.render("Testing", "", request.host()));
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * return the stats for last action on client as JSON
	 * 
	 * @return
	 */
	public Result clientRefreshStats(Request request) {
		ObjectNode on = Json.newObject();
		oocsiServer.getClients().stream().forEach(cl -> {
			on.put(cl.getName(), cl.lastAction());
		});

		return ok(on);
	}

}
