# Quarkus Metrics MCP Server — Skills Guide for LLM Agents

You have access to a Quarkus Metrics MCP server that provides 11 tools for observing JVM and application metrics. This guide tells you when and how to use each tool effectively.

## Quick Reference

| Tool | Use When | Required Input |
|------|----------|----------------|
| `verify_monitoring_capabilities` | First step — check what data sources are available | `podName` and/or `baseUrl` |
| `get_gc_metrics` | Investigating GC pressure, pause times, collection rates | `podName` and/or `baseUrl` |
| `get_jvm_memory_breakdown` | Checking heap usage, memory leaks, pool saturation | `podName` and/or `baseUrl` |
| `get_thread_pool_status` | Diagnosing thread exhaustion, deadlocks, pool sizing | `podName` and/or `baseUrl` |
| `get_http_latency_percentiles` | Analyzing API response times, error rates | `podName` and/or `baseUrl` |
| `get_quarkus_health` | Checking if app is healthy (liveness/readiness) | `baseUrl` |
| `query_metrics_from_prometheus` | Running custom PromQL queries | `query` |
| `fetch_raw_metrics_from_endpoint` | Browsing all available metrics from an app | `baseUrl` |
| `get_locally_monitored_apps` | Listing which apps are being monitored | (none) |
| `get_metrics_from_local_ringbuffer` | Viewing cached time-series data | `baseUrl` |
| `get_metrics_snapshot` | Getting a full observability overview in one call | `podName` and/or `baseUrl` |

## Decision Tree: Which Tool to Use

```
User asks about application health or performance
├── "Is the app healthy?" → get_quarkus_health
├── "What's the overall status?" → get_metrics_snapshot (one call, everything)
├── "Is it slow?" / "Why is latency high?"
│   ├── Start with get_http_latency_percentiles
│   ├── If p95 > 500ms, check get_gc_metrics (GC pauses?)
│   └── If GC is fine, check get_thread_pool_status (thread exhaustion?)
├── "Is it running out of memory?"
│   ├── get_jvm_memory_breakdown
│   └── Check heap_utilization_percent — if >80%, alert user
├── "Why is it crashing?" / OOMKilled
│   ├── get_jvm_memory_breakdown (heap near max?)
│   ├── get_gc_metrics (HIGH pressure = GC thrashing before OOM)
│   └── get_quarkus_health (readiness failing?)
├── "What metrics are available?" → fetch_raw_metrics_from_endpoint
├── "What apps are being monitored?" → get_locally_monitored_apps
├── Custom Prometheus query → query_metrics_from_prometheus
└── "Show me the raw cached data" → get_metrics_from_local_ringbuffer
```

## Understanding Inputs

### podName vs baseUrl

- **podName**: A Kubernetes pod name (e.g., `my-app-7d6d7cd77-zh2dp`). Enables Prometheus queries. Use when the app runs in Kubernetes with Prometheus scraping.
- **baseUrl**: The application's HTTP base URL (e.g., `http://localhost:8080`). Enables local storage and direct scrape. Use when you have network access to the app.
- **Both**: Provides the full three-tier fallback (Prometheus → Local Storage → Direct).

### timeWindow

Controls the time range for rate calculations and data freshness. Format: `5m`, `10m`, `1h`, `30s`. Default: `10m`.

- Use `5m` for recent, high-resolution data
- Use `10m` (default) for typical diagnostics
- Use `1h` for trend analysis over a longer period
- Use `30s` for near-real-time checks

## Workflow: First-Time Application Diagnostics

When a user asks you to look at an application for the first time, follow this sequence:

### Step 1: Discover what's available
```
verify_monitoring_capabilities(podName="...", baseUrl="...")
```
Check the `available_sources` array. This tells you whether Prometheus, local storage, and direct endpoint access are working.

### Step 2: Get the full picture
```
get_metrics_snapshot(podName="...", baseUrl="...", timeWindow="10m")
```
This single call returns GC, memory, threads, HTTP, and health data. Look for:
- `gc.gc_pressure` — should be `LOW`
- `memory.heap_utilization_percent` — should be <80%
- `threads.live_threads` vs `threads.peak_threads` — ratio shows thread pressure
- `http.latency_status` — should be `HEALTHY`
- `http.p95_latency_ms` — typical healthy range is <100ms

### Step 3: Drill down on problems
If the snapshot reveals issues, use the specific tool for deeper analysis:
- High GC pressure → `get_gc_metrics` with a shorter timeWindow to find the spike
- High memory → `get_jvm_memory_breakdown` to identify which pool is saturated
- High latency → `get_http_latency_percentiles` with a specific `uri` filter
- Thread issues → `get_thread_pool_status` for executor metrics

## Interpreting Responses

### Source Field

Every diagnostic response includes a `source` field:
- `PROMETHEUS` — data came from Prometheus API (most reliable, supports rate queries)
- `LOCAL_STORAGE` — data came from the in-memory ring buffer (good, slightly delayed)
- `DIRECT` — data came from a live scrape of `/q/metrics` (fallback, single snapshot)

### GC Metrics Interpretation

| Field | Healthy | Warning | Critical |
|-------|---------|---------|----------|
| `gc_pressure` | `LOW` | `MODERATE` | `HIGH` |
| `gc_overhead_percent` | <2% | 2-10% | >10% |
| `avg_pause_ms` | <50ms | 50-200ms | >200ms |
| `collections_per_second` | <1 | 1-10 | >10 |

### Memory Interpretation

| Field | Healthy | Warning | Critical |
|-------|---------|---------|----------|
| `heap_utilization_percent` | <60% | 60-80% | >80% |
| Pool `utilization_percent` | <70% | 70-90% | >90% |
| `max_bytes` = -1 | Unbounded pool (normal for Eden, Metaspace) | — | — |

### HTTP Latency Interpretation

| Field | Healthy | Degraded | Critical |
|-------|---------|----------|----------|
| `p95_latency_ms` | <500 | 500-1000 | >1000 |
| `latency_status` | `HEALTHY` | `DEGRADED` | `CRITICAL` |
| `error_ratio_percent` | <1% | 1-5% | >5% |

### Thread Interpretation

| Condition | Meaning |
|-----------|---------|
| `live_threads` ≈ `peak_threads` | Thread pool may be at capacity |
| `non_daemon_threads` very high | Possible thread leak |
| `peak_utilization_percent` > 80% | Application has been under heavy thread pressure |

## Common Scenarios

### "App is slow, investigate"
1. `get_metrics_snapshot` → check `http.p95_latency_ms`
2. If high latency: `get_http_latency_percentiles(uri="/specific/endpoint")` to isolate
3. `get_gc_metrics` → if `gc_pressure` is `HIGH`, GC pauses are causing latency
4. `get_jvm_memory_breakdown` → if heap >80%, memory pressure causes GC which causes latency

### "Is there a memory leak?"
1. `get_jvm_memory_breakdown` → check `heap_utilization_percent`
2. `get_metrics_from_local_ringbuffer` → look at `jvm_memory_used_bytes` over time (are values trending up?)
3. `get_gc_metrics` → increasing GC frequency with HIGH pressure suggests leak

### "Compare two time windows"
1. `get_gc_metrics(timeWindow="5m")` → recent
2. `get_gc_metrics(timeWindow="1h")` → historical average
3. Compare `collections_per_second` and `gc_overhead_percent` between them

### "What PromQL queries can I run?"
Use `query_metrics_from_prometheus` with any valid PromQL. Common queries:
- `rate(http_server_requests_seconds_count{pod="..."}[5m])` — request rate
- `jvm_memory_used_bytes{pod="...",area="heap"}` — heap by pool
- `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{pod="..."}[5m])) by (le))` — p99 latency
- `topk(5, rate(http_server_requests_seconds_count{pod="..."}[5m]))` — top 5 endpoints by traffic

## Error Handling

| Error | Cause | Action |
|-------|-------|--------|
| "Either podName or baseUrl must be provided" | Missing input | Ask user for pod name or app URL |
| "Prometheus is not configured" | No `PROMETHEUS_URL` set | Use `baseUrl` instead of `podName` |
| "Failed to fetch metrics from ..." | App unreachable | Check if app is running, URL is correct |
| Empty `collectors` array in GC | No GC events in window | Normal for idle apps; try wider `timeWindow` |
| `"source": "DIRECT"` when expecting Prometheus | Prometheus down or no cached data | Check with `verify_monitoring_capabilities` |
