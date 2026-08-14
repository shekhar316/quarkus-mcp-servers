package io.quarkiverse.mcp.servers.metrics;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.logging.Log;

/**
 * Resolves metrics through a three-tier fallback: Prometheus, Local Storage, Direct Endpoint.
 */
@ApplicationScoped
public class MetricsResolverService {

    /**
     * Source from which metrics were resolved.
     */
    public enum Source {
        PROMETHEUS,
        LOCAL_STORAGE,
        DIRECT
    }

    /**
     * Result of a metric resolution attempt.
     */
    public record MetricResolutionResult(Source source, List<PrometheusParser.MetricSample> samples,
            PrometheusResponse prometheusResponse, String rawText) {

        public boolean hasData() {
            return (samples != null && !samples.isEmpty())
                    || (prometheusResponse != null && prometheusResponse.hasData())
                    || (rawText != null && !rawText.isBlank());
        }
    }

    @Inject
    MetricsLocalStorage localStorage;

    @Inject
    MetricsPoller poller;

    @Inject
    MetricsConfig config;

    @Inject
    @RestClient
    PrometheusClient prometheusClient;

    /**
     * Resolves metrics using the three-tier fallback algorithm.
     *
     * @param podName Kubernetes pod name for Prometheus queries (nullable)
     * @param baseUrl base URL of the application (nullable)
     * @param metricQuery PromQL query or metric name
     * @return resolution result with data and source
     */
    public MetricResolutionResult resolve(String podName, String baseUrl, String metricQuery) {
        if ((podName == null || podName.isBlank()) && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalArgumentException("Either podName or baseUrl must be provided.");
        }

        if (podName != null && !podName.isBlank() && baseUrl != null && !baseUrl.isBlank()) {
            MetricResolutionResult res = tryPrometheus(podName, metricQuery);
            if (res != null && res.hasData()) {
                return res;
            }
            res = tryLocalStorage(baseUrl, metricQuery);
            if (res != null && res.hasData()) {
                return res;
            }
            return fetchDirectFiltered(baseUrl, metricQuery);
        }

        if (baseUrl != null && !baseUrl.isBlank()) {
            MetricResolutionResult res = tryLocalStorage(baseUrl, metricQuery);
            if (res != null && res.hasData()) {
                return res;
            }
            return fetchDirectFiltered(baseUrl, metricQuery);
        }

        // podName only
        MetricResolutionResult res = tryPrometheus(podName, metricQuery);
        if (res != null && res.hasData()) {
            return res;
        }
        return new MetricResolutionResult(Source.PROMETHEUS, Collections.emptyList(), null, null);
    }

    /**
     * Queries Prometheus directly with a PromQL query string.
     */
    public PrometheusResponse queryPrometheus(String query) {
        if (!isPrometheusConfigured()) {
            return null;
        }
        try {
            return prometheusClient.query(query, null);
        } catch (Exception e) {
            Log.debugf("Prometheus query failed: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Queries Prometheus with a range query.
     */
    public PrometheusResponse queryPrometheusRange(String query, String start, String end, String step) {
        if (!isPrometheusConfigured()) {
            return null;
        }
        try {
            return prometheusClient.queryRange(query, start, end, step);
        } catch (Exception e) {
            Log.debugf("Prometheus range query failed: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Checks if Prometheus is reachable.
     */
    public boolean isPrometheusReachable() {
        if (!isPrometheusConfigured()) {
            return false;
        }
        try {
            PrometheusResponse response = prometheusClient.query("up", null);
            return response != null && "success".equals(response.status());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Checks if a direct endpoint is reachable.
     */
    public boolean isEndpointReachable(String baseUrl) {
        String raw = poller.fetchRaw(baseUrl);
        return raw != null && !raw.isBlank();
    }

    boolean isPrometheusConfigured() {
        return config.prometheusUrl().isPresent() && !config.prometheusUrl().get().isBlank();
    }

    private MetricResolutionResult tryPrometheus(String podName, String metricQuery) {
        if (!isPrometheusConfigured()) {
            return null;
        }
        try {
            String query = metricQuery.contains("{")
                    ? metricQuery
                    : metricQuery + "{pod=\"" + podName + "\"}";
            PrometheusResponse response = prometheusClient.query(query, null);
            if (response != null && response.hasData()) {
                return new MetricResolutionResult(Source.PROMETHEUS, Collections.emptyList(), response, null);
            }
        } catch (Exception e) {
            Log.debugf("Prometheus query for pod %s failed: %s", podName, e.getMessage());
        }
        return null;
    }

    private MetricResolutionResult tryLocalStorage(String baseUrl, String metricQuery) {
        List<PrometheusParser.MetricSample> samples = localStorage.query(baseUrl, metricQuery);
        if (!samples.isEmpty()) {
            return new MetricResolutionResult(Source.LOCAL_STORAGE, samples, null, null);
        }

        if (localStorage.hasData(baseUrl)) {
            List<PrometheusParser.MetricSample> allSamples = localStorage.queryAll(baseUrl);
            List<PrometheusParser.MetricSample> filtered = allSamples.stream()
                    .filter(s -> s.name().contains(metricQuery))
                    .collect(Collectors.toList());
            if (!filtered.isEmpty()) {
                return new MetricResolutionResult(Source.LOCAL_STORAGE, filtered, null, null);
            }
        }
        return null;
    }

    private MetricResolutionResult fetchDirectFiltered(String baseUrl, String metricQuery) {
        MetricResolutionResult direct = fetchDirectSnapshot(baseUrl);
        if (direct.hasData() && metricQuery != null && !metricQuery.isBlank() && !metricQuery.contains("{")) {
            List<PrometheusParser.MetricSample> filtered = direct.samples().stream()
                    .filter(s -> s.name().equals(metricQuery))
                    .collect(Collectors.toList());
            return new MetricResolutionResult(Source.DIRECT, filtered, null, direct.rawText());
        }
        return direct;
    }

    private MetricResolutionResult fetchDirectSnapshot(String baseUrl) {
        String raw = poller.fetchRaw(baseUrl);
        if (raw != null && !raw.isBlank()) {
            long now = System.currentTimeMillis();
            List<PrometheusParser.MetricSample> samples = PrometheusParser.parse(raw, now);
            return new MetricResolutionResult(Source.DIRECT, samples, null, raw);
        }
        return new MetricResolutionResult(Source.DIRECT, Collections.emptyList(), null, null);
    }
}
