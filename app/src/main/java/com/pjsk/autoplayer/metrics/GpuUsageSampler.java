package com.pjsk.autoplayer.metrics;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GpuUsageSampler {
    private static final String KGSL_GPUBUSY = "/sys/class/kgsl/kgsl-3d0/gpubusy";
    private static final String[] PERCENT_PATHS = {
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/kernel/gpu/gpu_load"
    };
    private static final long SU_TIMEOUT_MS = 250;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final Map<String, Boolean> useSuByPath = new HashMap<>();

    private long previousBusy = -1L;
    private long previousTotal = -1L;

    public void reset() {
        previousBusy = -1L;
        previousTotal = -1L;
    }

    public Sample sample() {
        Sample kgslSample = sampleKgslBusy();
        if (kgslSample.available) {
            return kgslSample;
        }

        for (String path : PERCENT_PATHS) {
            Double percent = parsePercent(readText(path));
            if (percent != null) {
                return Sample.available(percent, path);
            }
        }

        return Sample.unavailable();
    }

    private Sample sampleKgslBusy() {
        long[] values = parseLongs(readText(KGSL_GPUBUSY), 2);
        if (values == null) {
            return Sample.unavailable();
        }

        long busy = values[0];
        long total = values[1];
        if (previousBusy < 0L || previousTotal < 0L || total <= previousTotal) {
            previousBusy = busy;
            previousTotal = total;
            return Sample.unavailable();
        }

        long busyDelta = Math.max(0L, busy - previousBusy);
        long totalDelta = Math.max(1L, total - previousTotal);
        previousBusy = busy;
        previousTotal = total;
        return Sample.available(busyDelta * 100.0 / totalDelta, KGSL_GPUBUSY);
    }

    private String readText(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        if (Boolean.TRUE.equals(useSuByPath.get(path))) {
            return readTextWithSu(path);
        }

        String direct = readTextDirect(path);
        if (direct != null) {
            return direct;
        }

        String viaSu = readTextWithSu(path);
        if (viaSu != null) {
            useSuByPath.put(path, true);
        }
        return viaSu;
    }

    private String readTextDirect(String path) {
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }

        try (FileInputStream input = new FileInputStream(file)) {
            return readStream(input);
        } catch (IOException ignored) {
            return null;
        }
    }

    private String readTextWithSu(String path) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + shellQuote(path)});
            if (!process.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return readStream(process.getInputStream());
        } catch (IOException e) {
            useSuByPath.put(path, false);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String readStream(java.io.InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name()).trim();
    }

    private String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    private long[] parseLongs(String text, int count) {
        if (text == null) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        long[] values = new long[count];
        int index = 0;
        while (index < count && matcher.find()) {
            try {
                values[index++] = Long.parseLong(matcher.group());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return index == count ? values : null;
    }

    private Double parsePercent(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            double value = Double.parseDouble(matcher.group());
            if (value > 100.0 && value <= 1000.0) {
                value /= 10.0;
            }
            return Math.max(0.0, Math.min(100.0, value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static final class Sample {
        public final boolean available;
        public final double percent;
        public final String source;
        public final long timestampMs;

        private Sample(boolean available, double percent, String source) {
            this.available = available;
            this.percent = Math.max(0.0, Math.min(100.0, percent));
            this.source = source == null ? "" : source;
            this.timestampMs = SystemClock.elapsedRealtime();
        }

        public static Sample available(double percent, String source) {
            return new Sample(true, percent, source);
        }

        public static Sample unavailable() {
            return new Sample(false, 0.0, "");
        }

        public String formatPercent() {
            return available ? String.format(Locale.US, "%.1f%%", percent) : "--";
        }
    }
}
