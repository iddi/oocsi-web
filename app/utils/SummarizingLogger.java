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

	private final Map<String, Integer> logStatements = new ConcurrentHashMap<>();
	private boolean dirty = false;

	public SummarizingLogger() {
		logger.info("-------------------------------------------------");
		logger.info("-- Starting the summarizing log                --");
		logger.info("-------------------------------------------------");
	}

	public synchronized void logSummary() {
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
			logStatements.merge(message, 1, (a, b) -> a + b);
			dirty = true;
		} catch (Exception e) {
			logger.error("logging issue", e);
		}
	}
}
