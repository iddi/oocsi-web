package model.actors;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.apache.pekko.actor.AbstractActor;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.Cancellable;
import org.apache.pekko.actor.Props;
import org.apache.pekko.actor.Status;

import com.google.inject.Inject;

import model.clients.ServiceRequestClient;
import nl.tue.id.oocsi.client.services.OOCSICall;
import nl.tue.id.oocsi.server.OOCSIServer;
import nl.tue.id.oocsi.server.model.Channel;
import nl.tue.id.oocsi.server.protocol.Message;
import nl.tue.id.oocsi.server.protocol.Protocol;

public class ServiceClientActor extends AbstractActor {

	private static final String WEBCALL_DATA = "webcall_data";
	private static final String WEBCALL_ACTION = "webcall";

	public static Props props(OOCSIServer server) {
		return Props.create(ServiceClientActor.class, server);
	}

	private final ServiceRequestClient requestClient;
	private final OOCSIServer server;
	private ActorRef replyTo;
	private Cancellable timeoutTask;

	@Inject
	public ServiceClientActor(OOCSIServer server) {
		this.server = server;
		this.requestClient = new ServiceRequestClient(server);
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(ServiceRequest.class, request -> {
					this.replyTo = sender();
					requestClient.reset();
					server.addClient(requestClient);
					Channel serviceClient = server.getChannel(request.service);

					if (serviceClient != null) {
						final ActorRef selfRef = self();
						requestClient.setResponseCallback(msg -> {
							selfRef.tell(new ServiceResponse(msg), ActorRef.noSender());
						});

						timeoutTask = context().system().scheduler().scheduleOnce(
								Duration.ofMillis(1800),
								self(),
								new ServiceTimeout(),
								context().dispatcher(),
								ActorRef.noSender());

						Message serviceMessage = new Message(requestClient.getName(), request.service);

						// add webcall action
						serviceMessage.addData(WEBCALL_ACTION, request.call);

						// add message handle
						serviceMessage.addData(OOCSICall.MESSAGE_HANDLE, request.service);
						String uuid = UUID.randomUUID().toString();
						serviceMessage.addData(OOCSICall.MESSAGE_ID, uuid);

						// try to parse the webcall_data
						if (request.data != null && request.data.length() > 0) {
							Map<String, Object> map = Protocol.parseJSONMessage(request.data);
							// if data could be parsed, use it directly
							if (map.size() > 0) {
								serviceMessage.data.putAll(map);
							}
							// include data verbatim if cannot be parsed as JSON
							else {
								serviceMessage.addData(WEBCALL_DATA, request.data);
							}
						}

						// send out to responder
						serviceClient.send(serviceMessage);
					} else {
						if (replyTo != null) {
							replyTo.tell(new Status.Failure(new IllegalArgumentException("Service not found: " + request.service)), self());
						}
					}
				})
				.match(ServiceResponse.class, resp -> {
					if (timeoutTask != null) {
						timeoutTask.cancel();
					}
					if (replyTo != null) {
						replyTo.tell(resp.message, self());
					}
				})
				.match(ServiceTimeout.class, timeout -> {
					if (replyTo != null) {
						if (requestClient.completedMessage != null) {
							replyTo.tell(requestClient.completedMessage, self());
						} else {
							replyTo.tell(new Status.Failure(new TimeoutException("Service timeout")), self());
						}
					}
				})
				.build();
	}

	@Override
	public void postStop() throws Exception {
		if (timeoutTask != null) {
			timeoutTask.cancel();
		}
		server.removeClient(requestClient);

		super.postStop();
	}

	private static class ServiceResponse {
		final Message message;
		ServiceResponse(Message message) {
			this.message = message;
		}
	}

	private static class ServiceTimeout {
	}

	public static class ServiceRequest {

		String service;
		String call;
		String data;

		public ServiceRequest(String service, String call, String data) {
			this.service = service;
			this.call = call;
			this.data = data;
		}
	}

}
