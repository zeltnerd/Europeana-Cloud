package eu.europeana.cloud.service.dps.storm.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;

public class Retriever {
    private final static Logger LOGGER = LoggerFactory.getLogger(Retriever.class);

    private static final int DEFAULT_RETRIES = 3;

    private static final int SLEEP_TIME = 5000;

    public static void retryOnError3Times(String errorMessage, Runnable runnable) {
        retryOnError3Times(errorMessage,()->{
            runnable.run();
            return null;
        });
    }

    public static <V,E extends Exception> V retryOnError3Times(String errorMessage, Callable<V> callable) throws E {
        return retryOnError(errorMessage, DEFAULT_RETRIES, SLEEP_TIME, callable);
    }

    public static <V, E extends Exception> V retryOnError(String errorMessage, int retryCount, int sleepTimeBetweenRetriesMs, Callable<V> callable) throws E {
        while (true) {
            try {
                return callable.call();
            } catch (Exception e) {
                if (retryCount-- > 0) {
                    LOGGER.warn(errorMessage +" Retries Left {} ", retryCount, e);
                    waitForSpecificTime(sleepTimeBetweenRetriesMs);
                } else {
                    LOGGER.error(errorMessage);
                    throw (E) e;
                }
            }
        }
    }

    public static void waitForSpecificTime(int milliSecond) {
        try {
            Thread.sleep(milliSecond);
        } catch (InterruptedException e) {
            throw new RuntimeException("Stop waiting for retry beacase interrupted flag set on Thread!",e);
        }
    }
}
