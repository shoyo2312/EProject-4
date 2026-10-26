package com.tiktok.adminservice.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Samples out of the Prometheus text exposition an actuator serves. */
public final class PrometheusText {

    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)\\{([^}]*)}\\s+(\\S+)");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private PrometheusText() {
    }

    /**
     * Every sample of {@code metric}, keyed by the value of its {@code label}, in the order served.
     * NaN stays NaN: it is what a gauge reports when it could not be read.
     */
    public static Map<String, Double> byLabel(String exposition, String metric, String label) {
        Map<String, Double> samples = new LinkedHashMap<>();
        for (String line : exposition.split("\n")) {
            Matcher sample = SAMPLE.matcher(line);
            if (!sample.find() || !sample.group(1).equals(metric)) {
                continue;
            }
            Matcher labels = LABEL.matcher(sample.group(2));
            while (labels.find()) {
                if (labels.group(1).equals(label)) {
                    samples.put(labels.group(2), value(sample.group(3)));
                    break;
                }
            }
        }
        return samples;
    }

    private static double value(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
