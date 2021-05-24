import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;

import nl.tue.id.oocsi.server.OOCSIServer;
import utils.SummarizingLogger;

public class Module extends AbstractModule {

	private static final Logger logger = LoggerFactory.getLogger(Module.class);

	@Override
	protected void configure() {
		try {
			// debug restart
			OOCSIServer os = OOCSIServer.getInstance();
			if (os != null) {
				os.destroyInstance();
			}

			// create logger
			SummarizingLogger sl = new SummarizingLogger(logger);
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
