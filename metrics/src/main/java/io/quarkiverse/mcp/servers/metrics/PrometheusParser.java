package io.quarkiverse.mcp.servers.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Prometheus exposition text format into structured metric samples.
 */
public final class PrometheusParser {

    /**
     * A single metric sample parsed from exposition text.
     */
    public record MetricSample(String name, Map<String, String> labels, double value, long timestamp) {
    }

    private PrometheusParser() {
    }

    /**
     * Parses raw Prometheus exposition text into a list of metric samples.
     *
     * @param rawBody the raw text body from /q/metrics
     * @param timestamp epoch millis to assign to all parsed samples
     * @return parsed metric samples
     */
    public static List<MetricSample> parse(String rawBody, long timestamp) {
        if (rawBody == null || rawBody.isBlank()) {
            return Collections.emptyList();
        }

        List<MetricSample> samples = new ArrayList<>();
        for (String line : rawBody.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            MetricSample sample = parseLine(line, timestamp);
            if (sample != null) {
                samples.add(sample);
            }
        }
        return samples;
    }

    private static MetricSample parseLine(String line, long timestamp) {
        try {
            String name;
            Map<String, String> labels;
            String valueStr;

            int braceOpen = line.indexOf('{');
            if (braceOpen >= 0) {
                name = line.substring(0, braceOpen);
                int braceClose = line.indexOf('}', braceOpen);
                if (braceClose < 0) {
                    return null;
                }
                labels = parseLabels(line.substring(braceOpen + 1, braceClose));
                valueStr = line.substring(braceClose + 1).trim();
            } else {
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx < 0) {
                    return null;
                }
                name = line.substring(0, spaceIdx);
                labels = Collections.emptyMap();
                valueStr = line.substring(spaceIdx + 1).trim();
            }

            int spaceInValue = valueStr.indexOf(' ');
            if (spaceInValue >= 0) {
                valueStr = valueStr.substring(0, spaceInValue);
            }

            double value = Double.parseDouble(valueStr);
            return new MetricSample(name, labels, value, timestamp);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, String> parseLabels(String labelsStr) {
        if (labelsStr == null || labelsStr.isBlank()) {
            return Collections.emptyMap();
        }

        Map<String, String> labels = new HashMap<>();
        int i = 0;
        while (i < labelsStr.length()) {
            int eqIdx = labelsStr.indexOf('=', i);
            if (eqIdx < 0) {
                break;
            }
            String key = labelsStr.substring(i, eqIdx).trim();

            int quoteStart = labelsStr.indexOf('"', eqIdx + 1);
            if (quoteStart < 0) {
                break;
            }
            int quoteEnd = findClosingQuote(labelsStr, quoteStart + 1);
            if (quoteEnd < 0) {
                break;
            }
            String val = labelsStr.substring(quoteStart + 1, quoteEnd);
            labels.put(key, val);

            i = quoteEnd + 1;
            if (i < labelsStr.length() && labelsStr.charAt(i) == ',') {
                i++;
            }
        }
        return labels;
    }

    private static int findClosingQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '"' && (i == start || s.charAt(i - 1) != '\\')) {
                return i;
            }
        }
        return -1;
    }
}
