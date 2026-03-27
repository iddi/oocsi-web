# OOCSI web

Distribution bundle for OOCSI web server with web socket support and all OOCSI tools for using OOCSI on the web. 

## Run the server

To use, please extract the release zip into a folder, adjust permissions on bin/oocsi-websocket (`chmod a+x bin/oocsi-websocket`), then execute the launcher (`./bin/oocsi-websocket`). To start the server on a different port than the default port 9000, use the addtional `-Dhttp.port=<port>` command line option.

Web socket connections can connect to `/ws`, a simple dashboard is available from `/dashboard`. All information is available from the server landing page `/`.

## Run with Docker or Podman

You can easily run an OOCSI server using Docker or Podman without needing to extract files or configure anything manually. The container images are available at `ghcr.io/iddi/oocsi-web`.

### Quick Start with Docker

```bash
docker run -d 
  --name oocsi-server 
  -p 9000:9000 
  -p 4444:4444 
  ghcr.io/iddi/oocsi-web:latest
```

Then access:
- **Web Dashboard**: http://localhost:9000/dashboard
- **Server Landing Page**: http://localhost:9000
- **WebSocket Endpoint**: ws://localhost:9000/ws

### Quick Start with Podman

```bash
podman run -d 
  --name oocsi-server 
  -p 9000:9000 
  -p 4444:4444 
  ghcr.io/iddi/oocsi-web:latest
```

Then access:
- **Web Dashboard**: http://localhost:9000/dashboard
- **Server Landing Page**: http://localhost:9000
- **WebSocket Endpoint**: ws://localhost:9000/ws

### Port Mappings

- **Port 9000**: HTTP server (web dashboard and WebSocket endpoint)
- **Port 4444**: OOCSI native protocol port

### Stopping the Container

```bash
# Docker
docker stop oocsi-server
docker rm oocsi-server

# Podman
podman stop oocsi-server
podman rm oocsi-server
```

### Custom Port Configuration

If you need to run the server on different ports, you can adjust the port mappings:

```bash
# Docker example: run on ports 8080 and 5555 instead
docker run -d \
  --name oocsi-server \
  -p 8080:9000 \
  -p 5555:4444 \
  ghcr.io/iddi/oocsi-web:latest
```

The format is `-p <host-port>:<container-port>`, so the container always uses 9000 and 4444 internally.

## OOCSI Websocket communication

First, include the JavaScript source (either as the [full library](https://github.com/iddi/oocsi-web/blob/master/public/js/oocsi-web.js) or as the [minified library](https://github.com/iddi/oocsi-web/blob/master/public/js/oocsi-web.min.js)) into the HTML page.

Then connect to an OOCSI server running a websockets adapter:
````javascript
OOCSI.connect("wss://_SERVER_ADDRESS_/ws");
````

You can send data to a channel or individual client (here: "John"):
````javascript
// JSON data object with two items, position and color
var data = {'position' : 90, 'color': 255};
    
// send data object to client "John"
OOCSI.send("John", data);
````

You can subscribe to a channel with a handler to handle messages:
````javascript
OOCSI.subscribe("testchannel", (msg) => {
  // handle message from "test channel"
  var position = msg.data.position;
  var color = msg.data.color;
});
````