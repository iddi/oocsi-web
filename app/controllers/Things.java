package controllers;

import java.io.InputStream;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import play.Environment;
import play.mvc.Controller;
import play.mvc.Http.Request;
import play.mvc.Result;

@Singleton
public class Things extends Controller {

    private final Environment environment;

    @Inject
    public Things(Environment environment) {
        this.environment = environment;
    }

    /**
     * Index page for Things, serves the index.html from the things directory
     */
    public Result index(Request request) {
        InputStream indexStream = environment.resourceAsStream("public/html/things/index.html");
        if (indexStream != null) {
            return ok(indexStream).as("text/html");
        }
        return ok(views.html.Things.index.render("Fantastic Things"));
    }

    /**
     * Serve a specific Thing or the fantastic-things.js library
     */
    public Result thing(Request request, String thingname) {
        // Security check: prevent path traversal (Input Sanitization)
        if (thingname == null || thingname.trim().isEmpty() ||
            thingname.contains(".." ) || thingname.contains("/") || thingname.contains("\\")) {
            return badRequest("Invalid thing name");
        }

        // Special mapping for the library file (mapping fantastic-things.js to the source file)
        if ("fantastic-things.js".equals(thingname)) {
            InputStream jsStream = environment.resourceAsStream("app/assets/js/things/oocsi-things.js");
            // If not found in app/assets (e.g. in JAR), try public/js/things if it was moved there
            if (jsStream == null) {
                 jsStream = environment.resourceAsStream("public/js/things/oocsi-things.js");
            }
            
            if (jsStream != null) {
                return ok(jsStream).as("application/javascript");
            }
        }

        // Resolve the requested file
        // 1. Try exact match
        InputStream is = environment.resourceAsStream("public/html/things/" + thingname);
        String filename = thingname;

        // 2. If exact match fails, try appending .html (e.g. /things/lampo -> lampo.html)
        if (is == null && !thingname.toLowerCase().endsWith(".html")) {
            is = environment.resourceAsStream("public/html/things/" + thingname + ".html");
            filename = thingname + ".html";
        }

        if (is != null) {
            return ok(is).as(getContentType(filename));
        }

        return notFound("Thing not found: " + thingname);
    }

    /**
     * Helper to guess content type based on extension
     */
    private String getContentType(String filename) {
        if (filename.endsWith(".html")) return "text/html";
        if (filename.endsWith(".js")) return "application/javascript";
        if (filename.endsWith(".css")) return "text/css";
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".mp4")) return "video/mp4";
        if (filename.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }
}