package com.pjsk.autoplayer.screen;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs the platform recorder through root, without creating another MediaProjection session. */
public final class RootScreenRecorder {
    private static final String TAG = "PJSK-RootRecorder";
    private static final String RECORDING_DIRECTORY = "/sdcard/Movies/PJSK";
    private static final String PID_FILE = "/data/local/tmp/pjsk-screenrecord.pid";
    private static final Pattern DISPLAY_ID_PATTERN =
            Pattern.compile("(?m)^Display\\s+(\\d+)\\s+\\(");

    private Process recordingProcess;
    private String outputPath;

    public synchronized boolean isRecording() {
        return recordingProcess != null && recordingProcess.isAlive();
    }

    public synchronized String start() throws IOException {
        if (isRecording()) {
            return outputPath;
        }
        String displayId = findPrimaryDisplayId();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        outputPath = RECORDING_DIRECTORY + "/PJSK_" + timestamp + ".mp4";
        String command = "mkdir -p " + RECORDING_DIRECTORY
                + "; rm -f " + shellQuote(outputPath)
                + "; echo $$ > " + PID_FILE
                + "; exec screenrecord --display-id " + displayId
                + " --bit-rate 12000000 --time-limit 0 " + shellQuote(outputPath);
        recordingProcess = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        watchProcess(recordingProcess, outputPath);
        return outputPath;
    }

    public synchronized String stop() {
        if (!isRecording()) {
            return null;
        }
        String savedPath = outputPath;
        runRootCommand("if [ -f " + PID_FILE + " ]; then kill -INT $(cat " + PID_FILE + "); fi");
        waitForStop(recordingProcess, 3000L);
        if (recordingProcess != null && recordingProcess.isAlive()) {
            recordingProcess.destroy();
        }
        recordingProcess = null;
        outputPath = null;
        runRootCommand("rm -f " + PID_FILE
                + "; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://"
                + shellQuote(savedPath) + " >/dev/null 2>&1");
        return savedPath;
    }

    private String findPrimaryDisplayId() throws IOException {
        String output = runRootForOutput("dumpsys SurfaceFlinger --display-id");
        Matcher matcher = DISPLAY_ID_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IOException("无法读取活动显示 ID");
        }
        return matcher.group(1);
    }

    private void watchProcess(Process process, String path) {
        Thread watcher = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    Log.w(TAG, "screenrecord stopped, code=" + exitCode + " path=" + path);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, "pjsk-screenrecord-watcher");
        watcher.start();
    }

    private String runRootForOutput(String command) throws IOException {
        Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        try {
            if (process.waitFor() != 0) {
                throw new IOException("root 命令执行失败：" + command);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("读取显示 ID 被中断", error);
        } finally {
            process.destroy();
        }
        return output.toString();
    }

    private void runRootCommand(String command) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            process.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            Log.w(TAG, "root command failed", error);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private void waitForStop(Process process, long timeoutMs) {
        if (process == null) {
            return;
        }
        try {
            process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }
}
