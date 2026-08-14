package io.quarkiverse.mcp.servers.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;

/**
 * MCP tools for Quarkus application metrics, health, and observability.
 */
@ApplicationScoped
public class MetricsTools {

    @Inject
    MetricsResolverService resolver;

    @Inject
    MetricsPoller poller;

    @Inject
    MetricsLocalStorage localStorage;

    @Inject
    MetricsConfig config;

    @Inject
    ObjectMapper mapper;

    @Tool(description = "Fetch Quarkus application health status including liveness and readiness checks. "
            + "Returns a flat JSON summary of overall status and individual check results.")
    String get_quarkus_health(
            @ToolArg(description = "Base URL of the Quarkus application (e.g. http://localhost:8080)") String baseUrl) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("base_url", baseUrl);

        String health = poller.fetchHealth(baseUrl, "q/health");
        String live = poller.fetchHealth(baseUrl, "q/health/live");
        String ready = poller.fetchHealth(baseUrl, "q/health/ready");

        result.put("health", parseJsonOrRaw(health));
        result.put("liveness", parseJsonOrRaw(live));
        result.put("readiness", parseJsonOrRaw(ready));

        return toJson(result);
    }

    @Tool(description = "Get garbage collection rate metrics including collections per second, GC overhead percentage, "
            + "average pause time, and pressure diagnosis over a configurable time window. "
            + "Uses rate(jvm_gc_pause_seconds_*) queries matching jvm-mcp patterns. "
            + "Resolves data via Prometheus, local ring buffer, or direct endpoint scrape.")
    String get_gc_metrics(
            @ToolArg(description = "Kubernetes pod name for Prometheus queries", required = false) String podName,
            @ToolArg(description = "Base URL of the application (e.g. http://localhost:8080)", required = false) String baseUrl,
            @ToolArg(description = "Time window for rate calculation (e.g. 5m, 10m, 1h). Default 10m.", required = false) String timeWindow) {

        validateAtLeastOne(podName, baseUrl);
        String window = (timeWindow != null && !timeWindow.isBlank()) ? timeWindow : "10m";
        long windowSeconds = parseWindowToSeconds(window);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("time_window", window);

        if (hasPod(podName) && resolver.isPrometheusConfigured()) {
            String pod = podFilter(podName);
            PrometheusResponse countRate = resolver.queryPrometheus(
                    "rate(jvm_gc_pause_seconds_count{" + pod + "}[" + window + "])");
            PrometheusResponse sumRate = resolver.queryPrometheus(
                    "rate(jvm_gc_pause_seconds_sum{" + pod + "}[" + window + "])");
            PrometheusResponse maxResp = resolver.queryPrometheus(
                    "jvm_gc_pause_seconds_max{" + pod + "}");

            result.put("source", "PROMETHEUS");

            if (countRate != null && countRate.hasData()) {
                List<Map<String, Object>> collectors = new ArrayList<>();
                double totalCountRate = 0;
                double totalSumRate = 0;

                for (PrometheusResponse.PrometheusResult r : countRate.data().result()) {
                    Map<String, Object> collector = new LinkedHashMap<>();
                    String action = r.metric().getOrDefault("action", "unknown");
                    String cause = r.metric().getOrDefault("cause", "unknown");
                    collector.put("action", action);
                    collector.put("cause", cause);

                    double cRate = r.scalarValue() != null ? r.scalarValue() : 0;
                    collector.put("collections_per_second", round(cRate));
                    collector.put("estimated_collections", Math.round(cRate * windowSeconds));
                    totalCountRate += cRate;

                    PrometheusResponse.PrometheusResult sumMatch = findMatchingGcResult(sumRate, action, cause);
                    if (sumMatch != null && sumMatch.scalarValue() != null) {
                        double sRate = sumMatch.scalarValue();
                        collector.put("gc_overhead_percent", round(sRate * 100));
                        totalSumRate += sRate;
                        if (cRate > 0) {
                            collector.put("avg_pause_ms", round((sRate / cRate) * 1000));
                        }
                    }

                    PrometheusResponse.PrometheusResult maxMatch = findMatchingGcResult(maxResp, action, cause);
                    if (maxMatch != null && maxMatch.scalarValue() != null) {
                        collector.put("max_pause_seconds", round(maxMatch.scalarValue()));
                    }

                    collectors.add(collector);
                }

                result.put("collectors", collectors);
                result.put("total_collections_per_second", round(totalCountRate));
                result.put("estimated_total_collections", Math.round(totalCountRate * windowSeconds));
                result.put("total_gc_overhead_percent", round(totalSumRate * 100));
                result.put("gc_pressure", totalSumRate > 0.1 ? "HIGH" : totalSumRate > 0.02 ? "MODERATE" : "LOW");
            }
        } else {
            MetricsResolverService.MetricResolutionResult countResult = resolver.resolve(podName, baseUrl,
                    "jvm_gc_pause_seconds_count");
            MetricsResolverService.MetricResolutionResult sumResult = resolver.resolve(podName, baseUrl,
                    "jvm_gc_pause_seconds_sum");
            result.put("source", countResult.source().name());

            if (!countResult.samples().isEmpty()) {
                buildGcFromSamples(result, countResult.samples(), sumResult.samples(), windowSeconds);
            }
        }

        return toJson(result);
    }

    @Tool(description = "Get JVM memory breakdown including heap and non-heap utilization percentages, "
            + "and per-pool saturation for Eden, Survivor, Old Gen, Metaspace, etc. "
            + "Uses jvm_memory_used_bytes/max_bytes with area and id labels. "
            + "Resolves data via Prometheus, local storage, or direct endpoint scrape.")
    String get_jvm_memory_breakdown(
            @ToolArg(description = "Kubernetes pod name for Prometheus queries", required = false) String podName,
            @ToolArg(description = "Base URL of the application (e.g. http://localhost:8080)", required = false) String baseUrl,
            @ToolArg(description = "Time window to filter data freshness (e.g. 5m, 10m, 1h). Default 10m.", required = false) String timeWindow) {

        validateAtLeastOne(podName, baseUrl);
        String window = (timeWindow != null && !timeWindow.isBlank()) ? timeWindow : "10m";
        long windowSeconds = parseWindowToSeconds(window);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("time_window", window);

        if (hasPod(podName) && resolver.isPrometheusConfigured()) {
            String pod = podFilter(podName);

            PrometheusResponse heapUsed = resolver.queryPrometheus(
                    "sum(jvm_memory_used_bytes{" + pod + ",area=\"heap\"})");
            PrometheusResponse heapMax = resolver.queryPrometheus(
                    "sum(jvm_memory_max_bytes{" + pod + ",area=\"heap\"})");
            PrometheusResponse nonHeapUsed = resolver.queryPrometheus(
                    "sum(jvm_memory_used_bytes{" + pod + ",area=\"nonheap\"})");
            PrometheusResponse poolUsed = resolver.queryPrometheus(
                    "jvm_memory_used_bytes{" + pod + "}");
            PrometheusResponse poolMax = resolver.queryPrometheus(
                    "jvm_memory_max_bytes{" + pod + "}");

            result.put("source", "PROMETHEUS");

            if (heapUsed != null && heapUsed.hasData()) {
                Double used = firstScalar(heapUsed);
                result.put("heap_used_bytes", used != null ? used.longValue() : 0);
            }
            if (heapMax != null && heapMax.hasData()) {
                Double max = firstScalar(heapMax);
                result.put("heap_max_bytes", max != null ? max.longValue() : 0);
            }
            if (heapUsed != null && heapUsed.hasData() && heapMax != null && heapMax.hasData()) {
                Double used = firstScalar(heapUsed);
                Double max = firstScalar(heapMax);
                if (used != null && max != null && max > 0) {
                    result.put("heap_utilization_percent", round((used / max) * 100));
                }
            }
            if (nonHeapUsed != null && nonHeapUsed.hasData()) {
                Double nonHeap = firstScalar(nonHeapUsed);
                if (nonHeap != null) {
                    result.put("nonheap_used_bytes", nonHeap.longValue());
                }
            }

            if (poolUsed != null && poolUsed.hasData()) {
                result.put("pools", buildPoolBreakdown(poolUsed, poolMax));
            }
        } else {
            MetricsResolverService.MetricResolutionResult usedResult = resolver.resolve(podName, baseUrl,
                    "jvm_memory_used_bytes");
            result.put("source", usedResult.source().name());

            if (!usedResult.samples().isEmpty()) {
                buildMemoryFromSamples(result, usedResult.samples(), windowSeconds);
            }
        }

        return toJson(result);
    }

    @Tool(description = "Get thread status including live, daemon, and peak thread counts, "
            + "plus executor pool metrics (queued tasks, active threads, pool size) if available. "
            + "Uses jvm_threads_live_threads, jvm_threads_daemon_threads, jvm_threads_peak_threads. "
            + "Resolves data via Prometheus, local storage, or direct endpoint scrape.")
    String get_thread_pool_status(
            @ToolArg(description = "Kubernetes pod name for Prometheus queries", required = false) String podName,
            @ToolArg(description = "Base URL of the application (e.g. http://localhost:8080)", required = false) String baseUrl,
            @ToolArg(description = "Time window to filter data freshness (e.g. 5m, 10m, 1h). Default 10m.", required = false) String timeWindow) {

        validateAtLeastOne(podName, baseUrl);
        String window = (timeWindow != null && !timeWindow.isBlank()) ? timeWindow : "10m";
        long windowSeconds = parseWindowToSeconds(window);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("time_window", window);

        if (hasPod(podName) && resolver.isPrometheusConfigured()) {
            String pod = podFilter(podName);

            PrometheusResponse live = resolver.queryPrometheus(
                    "jvm_threads_live_threads{" + pod + "}");
            PrometheusResponse daemon = resolver.queryPrometheus(
                    "jvm_threads_daemon_threads{" + pod + "}");
            PrometheusResponse peak = resolver.queryPrometheus(
                    "jvm_threads_peak_threads{" + pod + "}");

            result.put("source", "PROMETHEUS");

            Double liveVal = (live != null && live.hasData()) ? firstScalar(live) : null;
            Double daemonVal = (daemon != null && daemon.hasData()) ? firstScalar(daemon) : null;
            Double peakVal = (peak != null && peak.hasData()) ? firstScalar(peak) : null;

            if (liveVal != null) {
                result.put("live_threads", liveVal.longValue());
            }
            if (daemonVal != null) {
                result.put("daemon_threads", daemonVal.longValue());
            }
            if (peakVal != null) {
                result.put("peak_threads", peakVal.longValue());
            }
            if (liveVal != null && daemonVal != null) {
                result.put("non_daemon_threads", liveVal.longValue() - daemonVal.longValue());
            }
            if (liveVal != null && peakVal != null && peakVal > 0) {
                result.put("peak_utilization_percent", round((liveVal / peakVal) * 100));
            }
        } else {
            MetricsResolverService.MetricResolutionResult liveResult = resolver.resolve(podName, baseUrl,
                    "jvm_threads_live_threads");
            MetricsResolverService.MetricResolutionResult daemonResult = resolver.resolve(podName, baseUrl,
                    "jvm_threads_daemon_threads");
            MetricsResolverService.MetricResolutionResult peakResult = resolver.resolve(podName, baseUrl,
                    "jvm_threads_peak_threads");

            result.put("source", liveResult.source().name());
            long cutoff = System.currentTimeMillis() - (windowSeconds * 1000);
            buildThreadsFromSamples(result,
                    filterByWindow(liveResult.samples(), cutoff),
                    filterByWindow(daemonResult.samples(), cutoff),
                    filterByWindow(peakResult.samples(), cutoff));
        }

        addExecutorMetrics(result, podName, baseUrl);

        return toJson(result);
    }

    @Tool(description = "Get HTTP request latency percentiles (p95, p99) and error rates over a configurable time window. "
            + "Uses Micrometer http_server_requests_seconds_* histogram metrics. "
            + "Computes percentiles via Prometheus histogram_quantile or from local/direct histogram buckets.")
    String get_http_latency_percentiles(
            @ToolArg(description = "Kubernetes pod name for Prometheus queries", required = false) String podName,
            @ToolArg(description = "Base URL of the application (e.g. http://localhost:8080)", required = false) String baseUrl,
            @ToolArg(description = "URI pattern to filter (e.g. /api/users). If omitted, aggregates all URIs.", required = false) String uri,
            @ToolArg(description = "Time window for rate calculation (e.g. 5m, 10m, 1h). Default 10m.", required = false) String timeWindow) {

        validateAtLeastOne(podName, baseUrl);
        String window = (timeWindow != null && !timeWindow.isBlank()) ? timeWindow : "10m";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("time_window", window);

        if (hasPod(podName) && resolver.isPrometheusConfigured()) {
            String pod = podFilter(podName);
            String uriFilter = (uri != null && !uri.isBlank()) ? ",uri=\"" + uri + "\"" : "";
            String labels = pod + uriFilter;

            String p95Query = "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{"
                    + labels + "}[" + window + "])) by (le))";
            String p99Query = "histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{"
                    + labels + "}[" + window + "])) by (le))";
            String errorQuery = "sum(rate(http_server_requests_seconds_count{"
                    + labels + ",status=~\"5..\"}[" + window + "]))";
            String totalQuery = "sum(rate(http_server_requests_seconds_count{"
                    + labels + "}[" + window + "]))";

            PrometheusResponse p95 = resolver.queryPrometheus(p95Query);
            PrometheusResponse p99 = resolver.queryPrometheus(p99Query);
            PrometheusResponse errors = resolver.queryPrometheus(errorQuery);
            PrometheusResponse total = resolver.queryPrometheus(totalQuery);

            result.put("source", "PROMETHEUS");
            if (p95 != null && p95.hasData()) {
                Double val = firstScalar(p95);
                if (val != null) {
                    result.put("p95_latency_seconds", round(val));
                    result.put("p95_latency_ms", round(val * 1000));
                }
            }
            if (p99 != null && p99.hasData()) {
                Double val = firstScalar(p99);
                if (val != null) {
                    result.put("p99_latency_seconds", round(val));
                    result.put("p99_latency_ms", round(val * 1000));
                }
            }
            if (total != null && total.hasData()) {
                Double totalRate = firstScalar(total);
                if (totalRate != null) {
                    result.put("request_rate_per_sec", round(totalRate));
                }
            }
            if (errors != null && errors.hasData()) {
                Double errRate = firstScalar(errors);
                if (errRate != null) {
                    result.put("error_rate_5xx_per_sec", round(errRate));
                }
                if (total != null && total.hasData()) {
                    Double totalRate = firstScalar(total);
                    if (totalRate != null && totalRate > 0 && errRate != null) {
                        result.put("error_ratio_percent", round((errRate / totalRate) * 100));
                    }
                }
            }

            Double p95Val = (p95 != null && p95.hasData()) ? firstScalar(p95) : null;
            if (p95Val != null) {
                String alert = p95Val > 1.0 ? "CRITICAL" : p95Val > 0.5 ? "DEGRADED" : "HEALTHY";
                result.put("latency_status", alert);
            }
        } else {
            MetricsResolverService.MetricResolutionResult bucketResult = resolver.resolve(
                    podName, baseUrl, "http_server_requests_seconds_bucket");
            MetricsResolverService.MetricResolutionResult countResult = resolver.resolve(
                    podName, baseUrl, "http_server_requests_seconds_count");
            MetricsResolverService.MetricResolutionResult sumResult = resolver.resolve(
                    podName, baseUrl, "http_server_requests_seconds_sum");

            result.put("source", bucketResult.source().name());

            if (!bucketResult.samples().isEmpty()) {
                List<PrometheusParser.MetricSample> buckets = bucketResult.samples();
                List<PrometheusParser.MetricSample> counts = countResult.samples();
                List<PrometheusParser.MetricSample> sums = sumResult.samples();

                if (uri != null && !uri.isBlank()) {
                    buckets = buckets.stream()
                            .filter(s -> uri.equals(s.labels().get("uri")))
                            .collect(Collectors.toList());
                    counts = counts.stream()
                            .filter(s -> uri.equals(s.labels().get("uri")))
                            .collect(Collectors.toList());
                    sums = sums.stream()
                            .filter(s -> uri.equals(s.labels().get("uri")))
                            .collect(Collectors.toList());
                }

                Double p95 = computePercentileFromBuckets(buckets, 0.95);
                Double p99 = computePercentileFromBuckets(buckets, 0.99);

                if (p95 != null) {
                    result.put("p95_latency_seconds", round(p95));
                    result.put("p95_latency_ms", round(p95 * 1000));
                }
                if (p99 != null) {
                    result.put("p99_latency_seconds", round(p99));
                    result.put("p99_latency_ms", round(p99 * 1000));
                }

                double totalRequests = counts.stream()
                        .mapToDouble(PrometheusParser.MetricSample::value).sum();
                double totalTime = sums.stream()
                        .mapToDouble(PrometheusParser.MetricSample::value).sum();

                if (totalRequests > 0) {
                    result.put("total_requests", (long) totalRequests);
                    result.put("avg_latency_seconds", round(totalTime / totalRequests));
                    result.put("avg_latency_ms", round((totalTime / totalRequests) * 1000));
                }

                long errorCount = (long) counts.stream()
                        .filter(s -> s.labels().getOrDefault("status", "").startsWith("5"))
                        .mapToDouble(PrometheusParser.MetricSample::value).sum();
                result.put("error_count_5xx", errorCount);

                if (p95 != null) {
                    result.put("latency_status", p95 > 1.0 ? "CRITICAL" : p95 > 0.5 ? "DEGRADED" : "HEALTHY");
                }

                result.put("note", "Percentiles computed from cumulative histogram buckets (all-time, not windowed).");
            } else {
                result.put("note", "No HTTP metrics available from local storage or direct endpoint.");
            }
        }

        return toJson(result);
    }

    @Tool(description = "Execute a raw PromQL query against the configured Prometheus server. "
            + "Returns the full time-series result as JSON. Requires metrics.prometheus-url to be configured.")
    String query_metrics_from_prometheus(
            @ToolArg(description = "PromQL query string (e.g. 'rate(http_server_requests_seconds_count[5m])')") String query) {
        if (!resolver.isPrometheusConfigured()) {
            throw new ToolCallException("Prometheus is not configured. Set metrics.prometheus-url.");
        }

        PrometheusResponse response = resolver.queryPrometheus(query);
        if (response == null) {
            throw new ToolCallException("Prometheus query returned no response.");
        }
        return toJson(response);
    }

    @Tool(description = "Fetch raw metrics from a Quarkus application's /q/metrics endpoint. "
            + "Parses the Prometheus exposition text into flat JSON with metric names and values.")
    String fetch_raw_metrics_from_endpoint(
            @ToolArg(description = "Base URL of the application (e.g. http://localhost:8080)") String baseUrl) {
        String raw = poller.fetchRaw(baseUrl);
        if (raw == null || raw.isBlank()) {
            throw new ToolCallException("Failed to fetch metrics from " + baseUrl + "/q/metrics");
        }

        long now = System.currentTimeMillis();
        List<PrometheusParser.MetricSample> samples = PrometheusParser.parse(raw, now);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("base_url", baseUrl);
        result.put("total_metrics", samples.size());

        Map<String, Object> metrics = new LinkedHashMap<>();
        for (PrometheusParser.MetricSample sample : samples) {
            String key = sample.labels().isEmpty()
                    ? sample.name()
                    : sample.name() + sample.labels().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(",", "{", "}"));
            metrics.put(key, sample.value());
        }
        result.put("metrics", metrics);

        return toJson(result);
    }

    @Tool(description = "Verify which monitoring data sources are available for a given application. "
            + "Reports availability of Prometheus, local storage cache, and direct /q/metrics endpoint.")
    String verify_monitoring_capabilities(
            @ToolArg(description = "Kubernetes pod name for Prometheus queries", required = false) String podName,
            @ToolArg(description = "Base URL of the application (e.g. http://localhost:8080)", required = false) String baseUrl) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());

        Map<String, Object> capabilities = new LinkedHashMap<>();
        List<String> available = new ArrayList<>();

        if (hasPod(podName)) {
            capabilities.put("prometheus_configured", resolver.isPrometheusConfigured());
            if (resolver.isPrometheusConfigured()) {
                boolean reachable = resolver.isPrometheusReachable();
                capabilities.put("prometheus_reachable", reachable);
                if (reachable) {
                    PrometheusResponse check = resolver.queryPrometheus(
                            "up{pod=\"" + podName + "\"}");
                    boolean hasData = check != null && check.hasData();
                    capabilities.put("prometheus_has_pod_data", hasData);
                    if (hasData) {
                        available.add("PROMETHEUS");
                    }
                }
            }
        }

        if (baseUrl != null && !baseUrl.isBlank()) {
            boolean hasCached = localStorage.hasData(baseUrl);
            boolean endpointReachable = resolver.isEndpointReachable(baseUrl);
            capabilities.put("local_storage_has_data", hasCached);
            capabilities.put("endpoint_reachable", endpointReachable);
            if (hasCached) {
                available.add("LOCAL_STORAGE");
            }
            if (endpointReachable) {
                available.add("DIRECT");
            }
        }

        capabilities.put("available_sources", available);

        result.put("capabilities", capabilities);
        return toJson(result);
    }

    @Tool(description = "List all applications configured for local metrics monitoring. "
            + "Shows polling status, cache state, and endpoint reachability for each configured app.")
    String get_locally_monitored_apps() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("polling_enabled", config.poll().enabled());
        result.put("poll_interval_seconds", config.poll().interval().getSeconds());

        List<String> apps = config.apps().orElse(Collections.emptyList());
        List<Map<String, Object>> appList = new ArrayList<>();
        for (String app : apps) {
            String trimmed = app.trim();
            Map<String, Object> appInfo = new LinkedHashMap<>();
            appInfo.put("base_url", trimmed);
            appInfo.put("has_cached_data", localStorage.hasData(trimmed));
            appInfo.put("endpoint_reachable", resolver.isEndpointReachable(trimmed));
            appList.add(appInfo);
        }
        result.put("apps", appList);
        result.put("total_apps", appList.size());

        return toJson(result);
    }

    @Tool(description = "Retrieve all metrics from the local in-memory ring buffer for a monitored application. "
            + "Returns cached time-series data collected by the background poller with timestamps.")
    String get_metrics_from_local_ringbuffer(
            @ToolArg(description = "Base URL of the application (e.g. http://localhost:8080)") String baseUrl,
            @ToolArg(description = "Time window to filter samples (e.g. 5m, 10m, 1h). Default 10m.", required = false) String timeWindow) {

        String window = (timeWindow != null && !timeWindow.isBlank()) ? timeWindow : "10m";
        long cutoff = System.currentTimeMillis() - (parseWindowToSeconds(window) * 1000);

        List<PrometheusParser.MetricSample> samples = localStorage.queryAll(baseUrl).stream()
                .filter(s -> s.timestamp() >= cutoff)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("base_url", baseUrl);
        result.put("time_window", window);
        result.put("has_data", !samples.isEmpty());
        result.put("total_samples", samples.size());

        if (!samples.isEmpty()) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            for (PrometheusParser.MetricSample sample : samples) {
                String key = sample.labels().isEmpty()
                        ? sample.name()
                        : sample.name() + sample.labels().entrySet().stream()
                                .map(e -> e.getKey() + "=" + e.getValue())
                                .collect(Collectors.joining(",", "{", "}"));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("value", sample.value());
                entry.put("timestamp_ms", sample.timestamp());
                metrics.put(key, entry);
            }
            result.put("metrics", metrics);
        }

        return toJson(result);
    }

    @Tool(description = "Get a comprehensive metrics snapshot combining GC, memory, threads, HTTP latency, and health data. "
            + "Provide podName, baseUrl, or both. Resolves via Prometheus, local storage, or direct endpoint fallback.")
    String get_metrics_snapshot(
            @ToolArg(description = "Kubernetes pod name for Prometheus queries", required = false) String podName,
            @ToolArg(description = "Base URL of the application (e.g. http://localhost:8080)", required = false) String baseUrl,
            @ToolArg(description = "Time window for rate/freshness calculation (e.g. 5m, 10m, 1h). Default 10m.", required = false) String timeWindow) {

        validateAtLeastOne(podName, baseUrl);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now().toString());
        result.put("time_window", (timeWindow != null && !timeWindow.isBlank()) ? timeWindow : "10m");
        if (hasPod(podName)) {
            result.put("pod_name", podName);
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            result.put("base_url", baseUrl);
        }

        try {
            result.put("gc", mapper.readValue(get_gc_metrics(podName, baseUrl, timeWindow), Map.class));
        } catch (Exception e) {
            result.put("gc_error", e.getMessage());
        }

        try {
            result.put("memory", mapper.readValue(get_jvm_memory_breakdown(podName, baseUrl, timeWindow), Map.class));
        } catch (Exception e) {
            result.put("memory_error", e.getMessage());
        }

        try {
            result.put("threads", mapper.readValue(get_thread_pool_status(podName, baseUrl, timeWindow), Map.class));
        } catch (Exception e) {
            result.put("threads_error", e.getMessage());
        }

        try {
            result.put("http", mapper.readValue(get_http_latency_percentiles(podName, baseUrl, null, timeWindow), Map.class));
        } catch (Exception e) {
            result.put("http_error", e.getMessage());
        }

        if (baseUrl != null && !baseUrl.isBlank()) {
            try {
                result.put("health", mapper.readValue(get_quarkus_health(baseUrl), Map.class));
            } catch (Exception e) {
                result.put("health_error", e.getMessage());
            }
        }

        return toJson(result);
    }

    // --- Helper methods ---

    private void validateAtLeastOne(String podName, String baseUrl) {
        if ((podName == null || podName.isBlank()) && (baseUrl == null || baseUrl.isBlank())) {
            throw new ToolCallException("Either podName or baseUrl must be provided.");
        }
    }

    private boolean hasPod(String podName) {
        return podName != null && !podName.isBlank();
    }

    private String podFilter(String podName) {
        return "pod=\"" + podName + "\"";
    }

    private Double firstScalar(PrometheusResponse response) {
        if (response == null || !response.hasData()) {
            return null;
        }
        return response.data().result().get(0).scalarValue();
    }

    private PrometheusResponse.PrometheusResult findMatchingGcResult(
            PrometheusResponse response, String action, String cause) {
        if (response == null || !response.hasData()) {
            return null;
        }
        return response.data().result().stream()
                .filter(r -> action.equals(r.metric().get("action"))
                        && cause.equals(r.metric().get("cause")))
                .findFirst()
                .orElse(null);
    }

    private void buildGcFromSamples(Map<String, Object> result,
            List<PrometheusParser.MetricSample> countSamples,
            List<PrometheusParser.MetricSample> sumSamples,
            long windowSeconds) {

        long windowMs = windowSeconds * 1000;
        long cutoff = System.currentTimeMillis() - windowMs;

        Map<String, List<PrometheusParser.MetricSample>> countsByKey = countSamples.stream()
                .collect(Collectors.groupingBy(s -> s.labels().getOrDefault("action", "unknown")
                        + "|" + s.labels().getOrDefault("cause", "unknown")));
        Map<String, List<PrometheusParser.MetricSample>> sumsByKey = sumSamples.stream()
                .collect(Collectors.groupingBy(s -> s.labels().getOrDefault("action", "unknown")
                        + "|" + s.labels().getOrDefault("cause", "unknown")));

        List<Map<String, Object>> collectors = new ArrayList<>();
        double totalCountRate = 0;
        double totalSumRate = 0;

        for (Map.Entry<String, List<PrometheusParser.MetricSample>> entry : countsByKey.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            Map<String, Object> collector = new LinkedHashMap<>();
            collector.put("action", parts[0]);
            collector.put("cause", parts[1]);

            double[] countRate = computeRateFromSamples(entry.getValue(), cutoff);
            double[] sumRate = computeRateFromSamples(
                    sumsByKey.getOrDefault(entry.getKey(), Collections.emptyList()), cutoff);

            if (countRate != null) {
                collector.put("collections_per_second", round(countRate[0]));
                collector.put("estimated_collections", Math.round(countRate[0] * windowSeconds));
                totalCountRate += countRate[0];

                if (sumRate != null) {
                    collector.put("gc_overhead_percent", round(sumRate[0] * 100));
                    totalSumRate += sumRate[0];
                    if (countRate[0] > 0) {
                        collector.put("avg_pause_ms", round((sumRate[0] / countRate[0]) * 1000));
                    }
                }
            } else {
                PrometheusParser.MetricSample latestCount = latest(entry.getValue());
                if (latestCount != null) {
                    collector.put("total_collections_cumulative", (long) latestCount.value());
                    collector.put("note", "Single snapshot — rate requires 2+ samples in window");
                }
            }

            collectors.add(collector);
        }

        result.put("collectors", collectors);
        if (totalCountRate > 0) {
            result.put("total_collections_per_second", round(totalCountRate));
            result.put("estimated_total_collections", Math.round(totalCountRate * windowSeconds));
            result.put("total_gc_overhead_percent", round(totalSumRate * 100));
            result.put("gc_pressure", totalSumRate > 0.1 ? "HIGH" : totalSumRate > 0.02 ? "MODERATE" : "LOW");
        }
    }

    /**
     * Computes per-second rate from counter samples within a time window.
     * Returns {rate, latestValue} or null if fewer than 2 samples in window.
     */
    private double[] computeRateFromSamples(List<PrometheusParser.MetricSample> samples, long cutoff) {
        List<PrometheusParser.MetricSample> windowed = samples.stream()
                .filter(s -> s.timestamp() >= cutoff)
                .sorted(Comparator.comparingLong(PrometheusParser.MetricSample::timestamp))
                .collect(Collectors.toList());

        if (windowed.size() < 2) {
            return null;
        }

        PrometheusParser.MetricSample first = windowed.get(0);
        PrometheusParser.MetricSample last = windowed.get(windowed.size() - 1);
        double timeDiffSeconds = (last.timestamp() - first.timestamp()) / 1000.0;

        if (timeDiffSeconds <= 0) {
            return null;
        }

        double rate = (last.value() - first.value()) / timeDiffSeconds;
        return new double[] { rate, last.value() };
    }

    private void buildMemoryFromSamples(Map<String, Object> result,
            List<PrometheusParser.MetricSample> usedSamples, long windowSeconds) {

        long cutoff = System.currentTimeMillis() - (windowSeconds * 1000);

        Map<String, PrometheusParser.MetricSample> latestByPool = new LinkedHashMap<>();
        for (PrometheusParser.MetricSample s : usedSamples) {
            if (s.timestamp() < cutoff) {
                continue;
            }
            String key = s.labels().getOrDefault("area", "unknown") + "|"
                    + s.labels().getOrDefault("id", "unknown");
            PrometheusParser.MetricSample existing = latestByPool.get(key);
            if (existing == null || s.timestamp() > existing.timestamp()) {
                latestByPool.put(key, s);
            }
        }

        if (latestByPool.isEmpty()) {
            for (PrometheusParser.MetricSample s : usedSamples) {
                String key = s.labels().getOrDefault("area", "unknown") + "|"
                        + s.labels().getOrDefault("id", "unknown");
                PrometheusParser.MetricSample existing = latestByPool.get(key);
                if (existing == null || s.timestamp() > existing.timestamp()) {
                    latestByPool.put(key, s);
                }
            }
        }

        long heapTotal = 0;
        long nonHeapTotal = 0;
        List<Map<String, Object>> pools = new ArrayList<>();

        for (PrometheusParser.MetricSample s : latestByPool.values()) {
            String area = s.labels().getOrDefault("area", "unknown");
            if ("heap".equals(area)) {
                heapTotal += (long) s.value();
            } else if ("nonheap".equals(area)) {
                nonHeapTotal += (long) s.value();
            }

            Map<String, Object> pool = new LinkedHashMap<>();
            pool.put("pool_name", s.labels().getOrDefault("id", "unknown"));
            pool.put("area", area);
            pool.put("used_bytes", (long) s.value());
            pools.add(pool);
        }

        result.put("heap_used_bytes", heapTotal);
        result.put("nonheap_used_bytes", nonHeapTotal);
        if (!pools.isEmpty()) {
            result.put("pools", pools);
        }
    }

    private void buildThreadsFromSamples(Map<String, Object> result,
            List<PrometheusParser.MetricSample> liveSamples,
            List<PrometheusParser.MetricSample> daemonSamples,
            List<PrometheusParser.MetricSample> peakSamples) {

        PrometheusParser.MetricSample latestLive = latest(liveSamples);
        PrometheusParser.MetricSample latestDaemon = latest(daemonSamples);
        PrometheusParser.MetricSample latestPeak = latest(peakSamples);

        if (latestLive != null) {
            result.put("live_threads", (long) latestLive.value());
        }
        if (latestDaemon != null) {
            result.put("daemon_threads", (long) latestDaemon.value());
        }
        if (latestPeak != null) {
            result.put("peak_threads", (long) latestPeak.value());
        }
        if (latestLive != null && latestDaemon != null) {
            result.put("non_daemon_threads", (long) (latestLive.value() - latestDaemon.value()));
        }
    }

    private void addExecutorMetrics(Map<String, Object> result, String podName, String baseUrl) {
        try {
            Map<String, Object> executor = new LinkedHashMap<>();
            boolean hasData = false;

            if (hasPod(podName) && resolver.isPrometheusConfigured()) {
                String pod = podFilter(podName);
                PrometheusResponse queued = resolver.queryPrometheus("executor_queued_tasks{" + pod + "}");
                PrometheusResponse active = resolver.queryPrometheus("executor_active_threads{" + pod + "}");
                PrometheusResponse poolSize = resolver.queryPrometheus("executor_pool_size_threads{" + pod + "}");

                if (queued != null && queued.hasData()) {
                    executor.put("queued_tasks", firstScalar(queued).longValue());
                    hasData = true;
                }
                if (active != null && active.hasData()) {
                    executor.put("active_threads", firstScalar(active).longValue());
                    hasData = true;
                }
                if (poolSize != null && poolSize.hasData()) {
                    executor.put("pool_size", firstScalar(poolSize).longValue());
                    hasData = true;
                }
            }

            if (!hasData && baseUrl != null && !baseUrl.isBlank()) {
                MetricsResolverService.MetricResolutionResult queuedResult = resolver.resolve(null, baseUrl,
                        "executor_queued_tasks");
                if (!queuedResult.samples().isEmpty()) {
                    PrometheusParser.MetricSample s = latest(queuedResult.samples());
                    if (s != null) {
                        executor.put("queued_tasks", (long) s.value());
                        hasData = true;
                    }
                }
            }

            if (hasData) {
                result.put("executor", executor);
            }
        } catch (Exception e) {
            // executor metrics are optional
        }
    }

    private PrometheusParser.MetricSample latest(List<PrometheusParser.MetricSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        PrometheusParser.MetricSample latest = samples.get(0);
        for (PrometheusParser.MetricSample s : samples) {
            if (s.timestamp() > latest.timestamp()) {
                latest = s;
            }
        }
        return latest;
    }

    private Double computePercentileFromBuckets(
            List<PrometheusParser.MetricSample> bucketSamples, double percentile) {
        Map<Double, Double> buckets = new TreeMap<>();
        for (PrometheusParser.MetricSample s : bucketSamples) {
            String le = s.labels().get("le");
            if (le == null) {
                continue;
            }
            double bound = "+Inf".equals(le) ? Double.MAX_VALUE : Double.parseDouble(le);
            buckets.merge(bound, s.value(), Double::sum);
        }
        if (buckets.isEmpty()) {
            return null;
        }
        Double total = buckets.getOrDefault(Double.MAX_VALUE, 0.0);
        if (total <= 0) {
            return null;
        }
        double target = total * percentile;
        double prevBound = 0;
        double prevCount = 0;
        for (Map.Entry<Double, Double> entry : buckets.entrySet()) {
            if (entry.getKey() == Double.MAX_VALUE) {
                continue;
            }
            if (entry.getValue() >= target) {
                double bucketCount = entry.getValue() - prevCount;
                if (bucketCount <= 0) {
                    return entry.getKey();
                }
                double fraction = (target - prevCount) / bucketCount;
                return prevBound + fraction * (entry.getKey() - prevBound);
            }
            prevBound = entry.getKey();
            prevCount = entry.getValue();
        }
        return prevBound;
    }

    private List<PrometheusParser.MetricSample> filterByWindow(
            List<PrometheusParser.MetricSample> samples, long cutoff) {
        return samples.stream()
                .filter(s -> s.timestamp() >= cutoff)
                .collect(Collectors.toList());
    }

    private long parseWindowToSeconds(String window) {
        try {
            if (window.endsWith("m")) {
                return Long.parseLong(window.substring(0, window.length() - 1)) * 60;
            } else if (window.endsWith("h")) {
                return Long.parseLong(window.substring(0, window.length() - 1)) * 3600;
            } else if (window.endsWith("s")) {
                return Long.parseLong(window.substring(0, window.length() - 1));
            }
        } catch (NumberFormatException e) {
            // fall through to default
        }
        return 600;
    }

    private double round(Double value) {
        if (value == null) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private Object parseJsonOrRaw(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    private String toJson(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new ToolCallException("Failed to serialize response: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> buildPoolBreakdown(
            PrometheusResponse usedResponse, PrometheusResponse maxResponse) {
        List<Map<String, Object>> pools = new ArrayList<>();
        for (PrometheusResponse.PrometheusResult r : usedResponse.data().result()) {
            Map<String, Object> pool = new LinkedHashMap<>();
            String poolName = r.metric().getOrDefault("id", r.metric().getOrDefault("pool", "unknown"));
            String area = r.metric().getOrDefault("area", "unknown");
            pool.put("pool_name", poolName);
            pool.put("area", area);
            pool.put("used_bytes", r.scalarValue() != null ? r.scalarValue().longValue() : 0);

            if (maxResponse != null && maxResponse.hasData()) {
                PrometheusResponse.PrometheusResult maxResult = maxResponse.data().result().stream()
                        .filter(m -> poolName.equals(m.metric().getOrDefault("id",
                                m.metric().getOrDefault("pool", ""))))
                        .findFirst()
                        .orElse(null);
                if (maxResult != null && maxResult.scalarValue() != null) {
                    long maxVal = maxResult.scalarValue().longValue();
                    pool.put("max_bytes", maxVal);
                    if (maxVal > 0) {
                        pool.put("utilization_percent",
                                round((r.scalarValue() / maxResult.scalarValue()) * 100));
                    }
                }
            }
            pools.add(pool);
        }
        return pools;
    }
}
