package io.quarkiverse.mcp.servers.metrics;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Prometheus HTTP API response models.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrometheusResponse(String status, PrometheusData data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrometheusData(String resultType, List<PrometheusResult> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrometheusResult(Map<String, String> metric, List<Object> value, List<List<Object>> values) {

        /**
         * Returns the scalar value from an instant query result.
         */
        public Double scalarValue() {
            if (value != null && value.size() == 2) {
                return Double.parseDouble(value.get(1).toString());
            }
            return null;
        }

        /**
         * Returns the timestamp from an instant query result.
         */
        public Double scalarTimestamp() {
            if (value != null && value.size() == 2) {
                return Double.parseDouble(value.get(0).toString());
            }
            return null;
        }
    }

    /**
     * Checks if this response contains usable data.
     */
    public boolean hasData() {
        return "success".equals(status) && data != null && data.result() != null && !data.result().isEmpty();
    }
}
