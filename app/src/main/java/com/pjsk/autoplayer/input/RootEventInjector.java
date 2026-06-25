package com.pjsk.autoplayer.input;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowMetrics;

import com.pjsk.autoplayer.core.Config;
import com.pjsk.autoplayer.settings.AppSettings;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RootEventInjector implements TouchInjector {
    private static final String TAG = "PJSK-RootInput";

    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int EV_ABS = 3;
    private static final int SYN_REPORT = 0;
    private static final int BTN_TOOL_FINGER = 325;
    private static final int BTN_TOUCH = 330;
    private static final int ABS_MT_SLOT = 47;
    private static final int ABS_MT_TOUCH_MAJOR = 48;
    private static final int ABS_MT_PRESSURE = 58;
    private static final int ABS_MT_POSITION_X = 53;
    private static final int ABS_MT_POSITION_Y = 54;
    private static final int ABS_MT_TRACKING_ID = 57;

    private final boolean[] active = new boolean[10];
    private final int[] posX = new int[10];
    private final int[] posY = new int[10];
    private final int[] trackingIds = new int[10];
    private final long[] downNanos = new long[10];
    private final Context context;
    private final int displayW;
    private final int displayH;
    private final int displayRotation;
    private final double pxScale;
    private final ByteBuffer eventBuffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);

    private Process shell;
    private DataOutputStream shellInput;
    private String lastShellError = "";
    private String eventDevice = "/dev/input/event2";
    private int maxRawX;
    private int maxRawY;
    private int nextTrackingId = 1000;
    private boolean loggedFirstTouch;

    public RootEventInjector(Context context) {
        this.context = context.getApplicationContext();
        int[] display = getDisplaySize(context);
        displayW = display[0];
        displayH = display[1];
        displayRotation = getDisplayRotation(context);
        pxScale = Config.scaleForFrame(displayW);
        maxRawX = Math.max(1, displayW - 1);
        maxRawY = Math.max(1, displayH - 1);
        detectTouchDevice();
        Log.i(TAG, "touch device=" + eventDevice
                + " display=" + displayW + "x" + displayH
                + " raw=" + maxRawX + "x" + maxRawY
                + " rotation=" + displayRotation
                + " rawMapping=" + mappingLabel());
    }

    @Override
    public void down(int x, int y, int touchId) {
        try {
            doDown(x, y, touchId);
        } catch (IOException e) {
            Log.e(TAG, "down failed", e);
        }
    }

    @Override
    public void move(int x, int y, int touchId) {
        try {
            doMove(x, y, touchId);
        } catch (IOException e) {
            Log.e(TAG, "move failed", e);
        }
    }

    @Override
    public void up(int touchId) {
        try {
            doUp(touchId);
        } catch (IOException e) {
            Log.e(TAG, "up failed", e);
        }
    }

    @Override
    public void flickBatch(List<TouchPoint> points) {
        try {
            for (TouchPoint point : points) {
                doDown(point.x, point.y, point.touchId);
            }
            sleep(Config.FLICK_DOWN_SECONDS);
            for (int step = 1; step <= Config.FLICK_STEPS; step++) {
                double progress = step / (double) Config.FLICK_STEPS;
                for (TouchPoint point : points) {
                    int moveY = Math.max(0, (int) Math.round(point.y - scaledFlickDistance() * progress));
                    doMove(point.x, moveY, point.touchId);
                }
                sleep(Config.FLICK_STEP_SECONDS);
            }
            for (TouchPoint point : points) {
                int endY = Math.max(0, (int) Math.round(point.y - scaledFlickDistance()));
                doMove(point.x, endY, point.touchId);
                doUp(point.touchId);
            }
        } catch (IOException e) {
            Log.e(TAG, "flick batch failed", e);
        }
    }

    @Override
    public void flickHeld(int x, int y, int touchId) {
        try {
            int startY = posY[touchId];
            for (int step = 1; step <= Config.FLICK_STEPS; step++) {
                double progress = step / (double) Config.FLICK_STEPS;
                int moveY = Math.max(0, (int) Math.round(startY - scaledFlickDistance() * progress));
                doMove(x, moveY, touchId);
                sleep(Config.FLICK_STEP_SECONDS);
            }
            doUp(touchId);
        } catch (IOException e) {
            Log.e(TAG, "held flick failed", e);
        }
    }

    @Override
    public void shutdown() {
        try {
            if (shellInput != null) {
                shellInput.flush();
                shellInput.close();
            }
        } catch (IOException ignored) {
        }
        if (shell != null) {
            shell.destroy();
        }
    }

    private void doDown(int x, int y, int touchId) throws IOException {
        if (!validTouchId(touchId)) {
            return;
        }
        boolean hadActive = hasActiveTouch();
        int rx = rawX(x, y);
        int ry = rawY(x, y);
        if (!loggedFirstTouch) {
            loggedFirstTouch = true;
            Log.i(TAG, "first touch mapping=" + mappingLabel()
                    + " display=" + x + "," + y + " raw=" + rx + "," + ry);
        }
        trackingIds[touchId] = nextTrackingId++;
        active[touchId] = true;
        posX[touchId] = x;
        posY[touchId] = y;
        downNanos[touchId] = System.nanoTime();
        sendAbs(ABS_MT_SLOT, touchId);
        sendAbs(ABS_MT_TRACKING_ID, trackingIds[touchId]);
        sendAbs(ABS_MT_POSITION_X, rx);
        sendAbs(ABS_MT_POSITION_Y, ry);
        sendAbs(ABS_MT_TOUCH_MAJOR, 5);
        sendAbs(ABS_MT_PRESSURE, 50);
        if (!hadActive) {
            send(EV_KEY, BTN_TOOL_FINGER, 1);
            send(EV_KEY, BTN_TOUCH, 1);
        }
        sync();
    }

    private void doMove(int x, int y, int touchId) throws IOException {
        if (!validTouchId(touchId) || !active[touchId]) {
            return;
        }
        posX[touchId] = x;
        posY[touchId] = y;
        sendAbs(ABS_MT_SLOT, touchId);
        sendAbs(ABS_MT_POSITION_X, rawX(x, y));
        sendAbs(ABS_MT_POSITION_Y, rawY(x, y));
        sync();
    }

    private void doUp(int touchId) throws IOException {
        if (!validTouchId(touchId) || !active[touchId]) {
            return;
        }
        long elapsed = System.nanoTime() - downNanos[touchId];
        long remaining = (long) (Config.MIN_TAP_SECONDS * 1_000_000_000L) - elapsed;
        if (remaining > 0L) {
            try {
                Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        sendAbs(ABS_MT_SLOT, touchId);
        sendAbs(ABS_MT_TOUCH_MAJOR, 0);
        sendAbs(ABS_MT_PRESSURE, 0);
        sendAbs(ABS_MT_TRACKING_ID, -1);
        active[touchId] = false;
        if (!hasActiveTouch()) {
            send(EV_KEY, BTN_TOUCH, 0);
            send(EV_KEY, BTN_TOOL_FINGER, 0);
        }
        sync();
    }

    private void sendAbs(int code, int value) throws IOException {
        send(EV_ABS, code, value);
    }

    private void sync() throws IOException {
        send(EV_SYN, SYN_REPORT, 0);
        flushShell();
    }

    private synchronized void send(int type, int code, int value) throws IOException {
        ensureShell();
        long nowUs = System.currentTimeMillis() * 1000L;
        eventBuffer.clear();
        eventBuffer.putLong(nowUs / 1_000_000L);
        eventBuffer.putLong(nowUs % 1_000_000L);
        eventBuffer.putShort((short) type);
        eventBuffer.putShort((short) code);
        eventBuffer.putInt(value);
        shellInput.write(eventBuffer.array());
    }

    private synchronized void flushShell() throws IOException {
        ensureShell();
        shellInput.flush();
    }

    private synchronized void ensureShell() throws IOException {
        if (shell != null && shellInput != null) {
            if (shell.isAlive()) {
                return;
            }
            int exitCode = shell.exitValue();
            closeShell();
            throw new IOException("root input writer exited code=" + exitCode
                    + " stderr=" + lastShellError);
        }
        Log.i(TAG, "opening root input writer: " + eventDevice);
        shell = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat > " + eventDevice});
        shellInput = new DataOutputStream(shell.getOutputStream());
        startErrorReader(shell);
        sleep(0.03);
        if (!shell.isAlive()) {
            int exitCode = shell.exitValue();
            closeShell();
            throw new IOException("root input writer failed code=" + exitCode
                    + " stderr=" + lastShellError);
        }
    }

    private void closeShell() {
        try {
            if (shellInput != null) {
                shellInput.close();
            }
        } catch (IOException ignored) {
        }
        if (shell != null) {
            shell.destroy();
        }
        shellInput = null;
        shell = null;
    }

    private void startErrorReader(Process process) {
        lastShellError = "";
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastShellError = line;
                    Log.w(TAG, "root writer stderr: " + line);
                }
            } catch (IOException ignored) {
            }
        }, "pjsk-root-input-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean hasActiveTouch() {
        for (boolean value : active) {
            if (value) {
                return true;
            }
        }
        return false;
    }

    private boolean validTouchId(int touchId) {
        return touchId >= 0 && touchId < active.length;
    }

    private int rawX(int x, int y) {
        int mode = AppSettings.getTouchMappingMode(context);
        if (mode == AppSettings.TOUCH_MAPPING_LANDSCAPE_90) {
            int naturalX = Math.max(0, Math.min(displayH - 1, y));
            return Math.round(naturalX * (maxRawX / (float) Math.max(1, displayH - 1)));
        }
        if (mode == AppSettings.TOUCH_MAPPING_LANDSCAPE_270) {
            int naturalX = displayH - 1 - Math.max(0, Math.min(displayH - 1, y));
            return Math.round(naturalX * (maxRawX / (float) Math.max(1, displayH - 1)));
        }
        int clampedX = Math.max(0, Math.min(displayW - 1, x));
        return Math.round(clampedX * (maxRawX / (float) Math.max(1, displayW - 1)));
    }

    private int rawY(int x, int y) {
        int mode = AppSettings.getTouchMappingMode(context);
        if (mode == AppSettings.TOUCH_MAPPING_LANDSCAPE_90) {
            int naturalY = displayW - 1 - Math.max(0, Math.min(displayW - 1, x));
            return Math.round(naturalY * (maxRawY / (float) Math.max(1, displayW - 1)));
        }
        if (mode == AppSettings.TOUCH_MAPPING_LANDSCAPE_270) {
            int naturalY = Math.max(0, Math.min(displayW - 1, x));
            return Math.round(naturalY * (maxRawY / (float) Math.max(1, displayW - 1)));
        }
        int clampedY = Math.max(0, Math.min(displayH - 1, y));
        return Math.round(clampedY * (maxRawY / (float) Math.max(1, displayH - 1)));
    }

    private String mappingLabel() {
        return AppSettings.touchMappingLabel(AppSettings.getTouchMappingMode(context));
    }

    private void detectTouchDevice() {
        String output = runRootForOutput("getevent -lp 2>/dev/null");
        if (output.isEmpty()) {
            return;
        }

        String currentDevice = null;
        boolean currentLooksLikeTouch = false;
        int currentMaxX = -1;
        int currentMaxY = -1;
        String bestDevice = null;
        int bestMaxX = -1;
        int bestMaxY = -1;
        Pattern devicePattern = Pattern.compile("add device \\d+: (\\/dev\\/input\\/event\\d+)");
        Pattern maxPattern = Pattern.compile("max\\s+(-?\\d+)");
        for (String line : output.split("\n")) {
            Matcher deviceMatcher = devicePattern.matcher(line);
            if (deviceMatcher.find()) {
                if (isUsableTouchCandidate(currentDevice, currentLooksLikeTouch, currentMaxX, currentMaxY)) {
                    bestDevice = currentDevice;
                    bestMaxX = currentMaxX;
                    bestMaxY = currentMaxY;
                }
                currentDevice = deviceMatcher.group(1);
                currentLooksLikeTouch = false;
                currentMaxX = -1;
                currentMaxY = -1;
                continue;
            }
            String lower = line.toLowerCase();
            if (lower.contains("touch") || lower.contains("input_prop_direct")) {
                currentLooksLikeTouch = true;
            }
            if (line.contains("0035") || line.contains("ABS_MT_POSITION_X")) {
                Matcher m = maxPattern.matcher(line);
                if (m.find()) {
                    currentMaxX = Integer.parseInt(m.group(1));
                }
            }
            if (line.contains("0036") || line.contains("ABS_MT_POSITION_Y")) {
                Matcher m = maxPattern.matcher(line);
                if (m.find()) {
                    currentMaxY = Integer.parseInt(m.group(1));
                }
            }
        }
        if (isUsableTouchCandidate(currentDevice, currentLooksLikeTouch, currentMaxX, currentMaxY)) {
            bestDevice = currentDevice;
            bestMaxX = currentMaxX;
            bestMaxY = currentMaxY;
        }
        if (bestDevice != null) {
            eventDevice = bestDevice;
            maxRawX = bestMaxX;
            maxRawY = bestMaxY;
        }
    }

    private static boolean isUsableTouchCandidate(
            String device,
            boolean looksLikeTouch,
            int maxX,
            int maxY) {
        return device != null && looksLikeTouch && maxX > 0 && maxY > 0;
    }

    private String runRootForOutput(String command) {
        List<String> lines = new ArrayList<>();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "root command failed: " + command, e);
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
        return String.join("\n", lines);
    }

    private static int[] getDisplaySize(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return new int[]{1280, 720};
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = wm.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            return new int[]{bounds.width(), bounds.height()};
        }
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        return new int[]{metrics.widthPixels, metrics.heightPixels};
    }

    private static int getDisplayRotation(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return Surface.ROTATION_0;
        }
        return wm.getDefaultDisplay().getRotation();
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep(Math.max(0L, Math.round(seconds * 1000.0)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private double scaledFlickDistance() {
        return Config.FLICK_DISTANCE * pxScale;
    }
}
