import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import nl.tue.id.oocsi.server.OOCSIServer;
import play.inject.ApplicationLifecycle;
import utils.SummarizingLogger;

public class Module extends AbstractModule {

	@Override
	protected void configure() {
		// create logger
		SummarizingLogger sl = new SummarizingLogger();
		bind(SummarizingLogger.class).toInstance(sl);

		// bind Tutorial eagerly to start background clients (Echo, HTTP, Time, Test)
		bind(controllers.Tutorial.class).asEagerSingleton();
	}

	@Provides
	@Singleton
	public OOCSIServer provideOOCSIServer(ApplicationLifecycle lifecycle, SummarizingLogger sl) throws IOException {
		OOCSIServer existing = OOCSIServer.getInstance();
		if (existing != null) {
			existing.stop();
		}

		OOCSIServer server = new OOCSIServer(4444, 1000, true) {
			@Override
			protected void internalLog(String message) {
				sl.log(message);
			}
		};

		lifecycle.addStopHook(() -> {
			server.stop();
			return CompletableFuture.completedFuture(null);
		});

		return server;
	}
}
