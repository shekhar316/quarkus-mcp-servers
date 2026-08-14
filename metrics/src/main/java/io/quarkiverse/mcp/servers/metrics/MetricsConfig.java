package io.quarkiverse.mcp.servers.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for the Metrics MCP server.
 */
@ConfigMapping(prefix = "metrics")
public interface MetricsConfig {

    @WithDefault("5")
    int connectTimeoutSeconds();

    @WithDefault("10")
    int readTimeoutSeconds();

    Optional<String> prometheusUrl();

    Optional<List<String>> apps();

    Poll poll();

    Store store();

    interface Poll {
        @WithDefault("30s")
        Duration interval();

        @WithDefault("true")
        boolean enabled();
    }

    interface Store {
        @WithDefault("900")
        long retentionSeconds();

        @WithDefault("120")
        int maxSamples();
    }
}
