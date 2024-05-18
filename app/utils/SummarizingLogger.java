package utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class SummarizingLogger {

	private static final Logger logger = LoggerFactory.getLogger(SummarizingLogger.class);

	// initialize with null
	private Map<String, Integer> logStatements = null;
	private boolean dirty = false;

	public synchronized void logSummary() {
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
		try {
			if (logStatements != null) {
				logStatements.merge(message, 1, (a, b) -> a + b);
				dirty = true;
			} else {
				logger.info(message);
			}
		} catch (Exception e) {
			logger.error("logging issue", e);
		}
	}
}
