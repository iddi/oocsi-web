package controllers;

import com.google.inject.Inject;

import play.Environment;
import play.mvc.Controller;
import play.mvc.Result;

public class Tools extends Controller {

	private final Environment environment;

	@Inject
	public Tools(Environment env) {
		this.environment = env;

	}

	/**
	 * action to show an overview page for all tools and testing facilities
	 * 
	 * @return
	 */
	public Result index() {
		return ok(views.html.Tools.index.render("Tools", "", request().host()));
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * action to show a page to make your own dashboard
	 * 
	 * @return
	 */
	public Result dashboard() {
		return ok(views.html.Tools.dashboard.render("dashboard", "", request().host()));
	}

	/**
	 * action to show OOCSI mote page
	 * 
	 * @return
	 */
	public Result mote() {
		return ok(views.html.Tools.mote.render("OOCSImote", "", request().host()));
	}

	/**
	 * action to show OOCSI mote sharing page
	 * 
	 * @return
	 */
	public Result moteShare() {
		return ok(views.html.Tools.moteShare.render("OOCSImote", "", request().host()));
	}

	/**
	 * action to show OOCSI animation page
	 * 
	 * @return
	 */
	public Result animate() {
		return ok(views.html.Tools.animate.render("animOOCSI", "", request().host()));
	}

	/**
	 * action to show the data canvas page
	 * 
	 * @return
	 */
	public Result datacanvas(String token) {
		if (token == null || token.isEmpty() || !token.matches("^[A-Za-z0-9-_]+$")) {
			return ok(views.html.Tools.token.render("Please enter the channel token:", "Data Canvas",
					controllers.routes.Tools.datacanvas(token).relativeTo("/")));
		} else {
			return ok(views.html.Tools.datacanvas.render("Data Canvas", token, request().host()));
		}
	}

	/**
	 * action to show the IoTsim page
	 * 
	 * @return
	 */
	public Result iotsim() {
		return ok(views.html.Tools.iotsim.render("IoTsim", "", request().host()));
	}

	///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * action to show a test page for websocket experiments with OOCSI
	 * 
	 * @return
	 */
	public Result testJotted() {
		return ok(views.html.Test.testJotted.render("Testing", "", request().host(), environment.isProd()));
	}

	/**
	 * action to show a test page for websocket experiments with OOCSI
	 * 
	 * @return
	 */
	public Result testRaw() {
		return ok(views.html.Test.testRaw.render("Testing", "", request().host()));
	}

	/**
	 * action to show a test page for websocket experiments with OOCSI
	 * 
	 * @return
	 */
	public Result testVisual() {
		return ok(views.html.Test.testVisual.render("Testing", "", request().host()));
	}

}
