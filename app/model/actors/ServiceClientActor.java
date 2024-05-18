package model.actors;

import java.util.Map;
import java.util.UUID;

import org.apache.pekko.actor.AbstractActor;
import org.apache.pekko.actor.Props;

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

	@Inject
	public ServiceClientActor(OOCSIServer server) {
		this.server = server;
		this.requestClient = new ServiceRequestClient(server);
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder().match(ServiceRequest.class, request -> {
			server.addClient(requestClient);
			Channel serviceClient = server.getChannel(request.service);

			if (serviceClient != null) {

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

				int i = 0;
				while (!requestClient.completed() && i < 9) {
					i++;
					Thread.sleep(200);
				}

				if (requestClient.completedMessage != null) {
					sender().tell(requestClient.completedMessage, self());
				}
			}
		}).build();
	}

	@Override
	public void postStop() throws Exception {
		server.removeClient(requestClient);

		super.postStop();
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
