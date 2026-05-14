package fr.cobbledollars.commandshops.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class PerfHarness {
    private PerfHarness() {
    }

    public static Measurement measure(String scenario, int warmupIterations, int measuredIterations, CheckedRunnable action)
            throws Exception {
        if (warmupIterations < 0) {
            throw new IllegalArgumentException("warmupIterations must be >= 0");
        }
        if (measuredIterations <= 0) {
            throw new IllegalArgumentException("measuredIterations must be > 0");
        }

        for (int index = 0; index < warmupIterations; index++) {
            action.run();
        }

        long[] samples = new long[measuredIterations];
        long totalNanos = 0L;
        for (int index = 0; index < measuredIterations; index++) {
            long startNanos = System.nanoTime();
            action.run();
            long elapsedNanos = System.nanoTime() - startNanos;
            samples[index] = elapsedNanos;
            totalNanos += elapsedNanos;
        }

        return summarizeSamples(scenario, warmupIterations, samples);
    }

    public static Measurement summarizeSamples(String scenario, int warmupIterations, long[] samples) {
        if (samples.length == 0) {
            throw new IllegalArgumentException("samples must not be empty");
        }

        long totalNanos = 0L;
        for (long sample : samples) {
            totalNanos += sample;
        }

        long[] sortedSamples = samples.clone();
        Arrays.sort(sortedSamples);
        long minNanos = sortedSamples[0];
        long medianNanos = sortedSamples[(sortedSamples.length - 1) / 2];
        long p95Nanos = sortedSamples[(int) Math.ceil(sortedSamples.length * 0.95d) - 1];
        long maxNanos = sortedSamples[sortedSamples.length - 1];
        long averageNanos = totalNanos / samples.length;
        return new Measurement(scenario, warmupIterations, samples.length, minNanos, averageNanos, medianNanos, p95Nanos, maxNanos);
    }

    public static Path writeReport(String fileStem, String title, List<Measurement> measurements) throws IOException {
        Path reportDirectory = BenchConfigSupport.projectDir().resolve("build").resolve("reports").resolve("perf");
        Files.createDirectories(reportDirectory);

        Path markdownReport = reportDirectory.resolve(fileStem + ".md");
        Path jsonReport = reportDirectory.resolve(fileStem + ".json");
        String generatedAt = Instant.now().toString();

        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(title).append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("- Generated at: `").append(generatedAt).append('`').append(System.lineSeparator());
        markdown.append("- Project dir: `").append(BenchConfigSupport.projectDir()).append('`').append(System.lineSeparator());
        markdown.append(System.lineSeparator());
        markdown.append("| Scenario | Warmup | Iterations | Min ms | Avg ms | Median ms | P95 ms | Max ms |").append(System.lineSeparator());
        markdown.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |").append(System.lineSeparator());
        for (Measurement measurement : measurements) {
            markdown.append("| ").append(measurement.scenario())
                    .append(" | ").append(measurement.warmupIterations())
                    .append(" | ").append(measurement.measuredIterations())
                    .append(" | ").append(formatMillis(measurement.minNanos()))
                    .append(" | ").append(formatMillis(measurement.averageNanos()))
                    .append(" | ").append(formatMillis(measurement.medianNanos()))
                    .append(" | ").append(formatMillis(measurement.p95Nanos()))
                    .append(" | ").append(formatMillis(measurement.maxNanos()))
                    .append(" |").append(System.lineSeparator());
        }
        Files.writeString(markdownReport, markdown.toString());

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"title\": \"").append(escapeJson(title)).append("\",\n");
        json.append("  \"generatedAt\": \"").append(escapeJson(generatedAt)).append("\",\n");
        json.append("  \"measurements\": [\n");
        for (int index = 0; index < measurements.size(); index++) {
            Measurement measurement = measurements.get(index);
            json.append("    {\n");
            json.append("      \"scenario\": \"").append(escapeJson(measurement.scenario())).append("\",\n");
            json.append("      \"warmupIterations\": ").append(measurement.warmupIterations()).append(",\n");
            json.append("      \"measuredIterations\": ").append(measurement.measuredIterations()).append(",\n");
            json.append("      \"minNanos\": ").append(measurement.minNanos()).append(",\n");
            json.append("      \"averageNanos\": ").append(measurement.averageNanos()).append(",\n");
            json.append("      \"medianNanos\": ").append(measurement.medianNanos()).append(",\n");
            json.append("      \"p95Nanos\": ").append(measurement.p95Nanos()).append(",\n");
            json.append("      \"maxNanos\": ").append(measurement.maxNanos()).append('\n');
            json.append("    }");
            if (index + 1 < measurements.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  ]\n");
        json.append("}\n");
        Files.writeString(jsonReport, json.toString());
        return markdownReport;
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0d);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    public record Measurement(
            String scenario,
            int warmupIterations,
            int measuredIterations,
            long minNanos,
            long averageNanos,
            long medianNanos,
            long p95Nanos,
            long maxNanos
    ) {
    }
}
