package co.edu.corhuila.opti.workflow.adapter;

import org.slf4j.MDC;

/** Access to the correlation id of the request in flight (also written in every log line). */
public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private Correlation() {
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "n/a" : id;
    }
}
