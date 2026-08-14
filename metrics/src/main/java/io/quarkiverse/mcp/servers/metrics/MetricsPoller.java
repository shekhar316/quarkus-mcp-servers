package io.quarkiverse.mcp.servers.metrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;

/**
 * Periodically polls configured application endpoints for Prometheus metrics.
 */
@ApplicationScoped
public class MetricsPoller {

    @Inject
    MetricsConfig config;

    @Inject
    MetricsLocalStorage storage;

    private volatile HttpClient httpClient;

    private HttpClient getClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds()))
                    .build();
        }
        return httpClient;
    }

    @Scheduled(every = "${metrics.poll.interval:30s}", identity = "metrics-poller", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (!config.poll().enabled()) {
            return;
        }
        List<String> apps = config.apps().orElse(null);
        if (apps == null || apps.isEmpty()) {
            return;
        }
        for (String baseUrl : apps) {
            pollApp(baseUrl.trim());
        }
    }

    private void pollApp(String baseUrl) {
        String metricsUrl = baseUrl.endsWith("/") ? baseUrl + "q/metrics" : baseUrl + "/q/metrics";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(metricsUrl))
                    .timeout(Duration.ofSeconds(config.readTimeoutSeconds()))
                    .GET()
                    .build();

            HttpResponse<String> response = getClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                long now = System.currentTimeMillis();
                List<PrometheusParser.MetricSample> samples = PrometheusParser.parse(response.body(), now);
                storage.store(baseUrl, samples);
                Log.debugf("Polled %d metrics from %s", samples.size(), metricsUrl);
            } else {
                Log.warnf("Polling %s returned status %d", metricsUrl, response.statusCode());
            }
        } catch (Exception e) {
            Log.warnf("Failed to poll %s: %s", metricsUrl, e.getMessage());
        }
    }

    /**
     * Fetches metrics from an endpoint on demand (non-scheduled).
     */
    public String fetchRaw(String baseUrl) {
        String metricsUrl = baseUrl.endsWith("/") ? baseUrl + "q/metrics" : baseUrl + "/q/metrics";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(metricsUrl))
                    .timeout(Duration.ofSeconds(config.readTimeoutSeconds()))
                    .GET()
                    .build();
            HttpResponse<String> response = getClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
            return null;
        } catch (Exception e) {
            Log.debugf("Failed to fetch %s: %s", metricsUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches a health endpoint on demand.
     */
    public String fetchHealth(String baseUrl, String path) {
        String url = baseUrl.endsWith("/") ? baseUrl + path : baseUrl + "/" + path;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(config.readTimeoutSeconds()))
                    .GET()
                    .build();
            HttpResponse<String> response = getClient().send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }
}
