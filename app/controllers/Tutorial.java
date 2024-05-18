package controllers;

import org.apache.pekko.actor.ActorSystem;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import model.clients.EchoClient;
import model.clients.HTTPRequestClient;
import model.clients.HeyOOCSIClient;
import model.generators.TestChannelGenerator;
import model.generators.TimeChannelGenerator;
import nl.tue.id.oocsi.server.OOCSIServer;
import play.Environment;
import play.libs.ws.WSClient;
import play.mvc.Controller;
import play.mvc.Result;
import scala.concurrent.ExecutionContext;

@Singleton
public class Tutorial extends Controller {

	private Environment environment;

	@Inject
	public Tutorial(Environment environment, OOCSIServer server, ActorSystem system, ExecutionContext ectx,
	        WSClient wsClient, HeyOOCSIClient heyOOCSIClient) {
		this.environment = environment;

		// start client
		new EchoClient("echo", server);

		// start HTTP request client, inject ws client
		new HTTPRequestClient("http-web-request", server, wsClient);

		// start generators
		new TestChannelGenerator(server, system, ectx);
		new TimeChannelGenerator(server, system, ectx);
	}

	public Result index() {
		return ok(views.html.Tutorial.index.render(environment.isProd()));
	}

	public Result paint_the_canvas() {
		return ok(views.html.Tutorial.paint_the_canvas.render(environment.isProd()));
	}
}
