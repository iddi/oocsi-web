import java.io.IOException;

import com.google.inject.AbstractModule;

import nl.tue.id.oocsi.server.OOCSIServer;
import utils.SummarizingLogger;

public class Module extends AbstractModule {

	@Override
	protected void configure() {
		try {
			// create logger
			SummarizingLogger sl = new SummarizingLogger();
			bind(SummarizingLogger.class).toInstance(sl);

			// create OOCSI server
			bind(OOCSIServer.class).toInstance(new OOCSIServer(4444, 1000, true) {
				@Override
				protected void internalLog(String message) {
					sl.log(message);
				}
			});
		} catch (IOException e) {
		}
	}
}
