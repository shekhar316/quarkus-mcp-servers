package io.quarkiverse.mcp.servers.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Thread-safe in-memory ring buffer for metric samples keyed by baseUrl and metric name.
 */
@ApplicationScoped
public class MetricsLocalStorage {

    @Inject
    MetricsConfig config;

    private final ConcurrentHashMap<String, Deque<PrometheusParser.MetricSample>> store = new ConcurrentHashMap<>();

    /**
     * Stores a batch of metric samples for a given base URL.
     */
    public void store(String baseUrl, List<PrometheusParser.MetricSample> samples) {
        for (PrometheusParser.MetricSample sample : samples) {
            String key = baseUrl + ":" + sample.name();
            store.compute(key, (k, deque) -> {
                if (deque == null) {
                    deque = new ArrayDeque<>();
                }
                deque.addLast(sample);
                while (deque.size() > config.store().maxSamples()) {
                    deque.removeFirst();
                }
                return deque;
            });
        }
    }

    /**
     * Queries samples for a specific metric from a given base URL, pruning expired entries.
     */
    public List<PrometheusParser.MetricSample> query(String baseUrl, String metricName) {
        String key = baseUrl + ":" + metricName;
        Deque<PrometheusParser.MetricSample> deque = store.get(key);
        if (deque == null) {
            return Collections.emptyList();
        }
        return pruneAndCopy(deque);
    }

    /**
     * Returns all stored samples for a given base URL, pruning expired entries.
     */
    public List<PrometheusParser.MetricSample> queryAll(String baseUrl) {
        String prefix = baseUrl + ":";
        List<PrometheusParser.MetricSample> result = new ArrayList<>();
        for (Map.Entry<String, Deque<PrometheusParser.MetricSample>> entry : store.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.addAll(pruneAndCopy(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * Checks if any data exists for the given base URL.
     */
    public boolean hasData(String baseUrl) {
        String prefix = baseUrl + ":";
        return store.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    private List<PrometheusParser.MetricSample> pruneAndCopy(Deque<PrometheusParser.MetricSample> deque) {
        long cutoff = System.currentTimeMillis() - (config.store().retentionSeconds() * 1000);
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().timestamp() < cutoff) {
                deque.removeFirst();
            }
            return new ArrayList<>(deque);
        }
    }
}
