package utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.inject.Singleton;

import org.slf4j.Logger;

@Singleton
public class SummarizingLogger {

	private final Logger logger;

	// initialize with null
	private Map<String, Integer> logStatements = null;
	private boolean dirty = false;

	public SummarizingLogger(Logger logger) {
		this.logger = logger;
	}

	public void logSummary() {
		if (logStatements == null) {
			logStatements = new ConcurrentHashMap<>();
			logger.info("-------------------------------------------------");
			logger.info("-- Starting the summarizing log                --");
			logger.info("-------------------------------------------------");
		}

		// flush logger
		if (dirty) {
			logStatements.entrySet().stream().forEach(e -> {
				String message = e.getKey() + " | " + e.getValue();
				logger.info(message);
			});

			// print summary at the end
			long events = logStatements.values().stream().collect(Collectors.summarizingInt(i -> i)).getSum();
			logger.info("------------------------------------------------- " + (events / 60) + " events/sec");

			// reset
			logStatements.clear();
			dirty = false;
		}
	}

	public synchronized void log(String message) {
		if (logStatements != null) {
			logStatements.merge(message, 1, (a, b) -> a + b);
			dirty = true;
		} else {
			logger.info(message);
		}
	}
}
