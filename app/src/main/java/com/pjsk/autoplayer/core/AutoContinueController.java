package com.pjsk.autoplayer.core;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.pjsk.autoplayer.input.TouchInjector;

public final class AutoContinueController {
    private static final String TAG = "PJSK-AutoContinue";

    private static final int TOUCH_ID = 9;
    private static final long IDLE_DETECTION_INTERVAL_MS = 500;
    private static final long ACTIVE_DETECTION_INTERVAL_MS = 250;
    private static final long START_RESUME_DELAY_MS = 1800;
    private static final long TAP_REPEAT_MS = 1200;

    private static final double CONFIRM_X = 1415.0 / 1920.0;
    private static final double CONFIRM_Y = 725.0 / 887.0;
    private static final double START_X = 1415.0 / 1920.0;
    private static final double START_Y = 675.0 / 887.0;

    private final TouchInjector injector;
    private int state = State.IDLE;
    private long lastDetectMs;
    private long lastTapMs;
    private long waitUntilMs;

    public AutoContinueController(TouchInjector injector) {
        this.injector = injector;
    }

    public void reset() {
        state = State.IDLE;
        lastDetectMs = 0L;
        lastTapMs = 0L;
        waitUntilMs = 0L;
    }

    public boolean shouldSuppressGameRecognition() {
        return state != State.IDLE;
    }

    public void onFrame(
            Bitmap frame,
            int displayWidth,
            int displayHeight,
            boolean enabled,
            boolean clickBlocked) {
        if (!enabled || clickBlocked || frame == null || frame.isRecycled()) {
            if (!enabled) {
                reset();
            }
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastDetectMs < detectionIntervalMs()) {
            return;
        }
        lastDetectMs = now;

        switch (state) {
            case State.IDLE:
                if (isLiveClear(frame)) {
                    state = State.GAME_ENDED;
                    lastTapMs = 0L;
                    Log.i(TAG, "LIVE CLEAR detected");
                }
                break;

            case State.GAME_ENDED:
            case State.CONFIRM_SENT:
                handleStartFlow(frame, displayWidth, displayHeight, now);
                break;

            case State.START_SENT:
                if (now >= waitUntilMs) {
                    reset();
                    Log.i(TAG, "game recognition resumed");
                }
                break;
        }
    }

    private void handleStartFlow(Bitmap frame, int displayWidth, int displayHeight, long now) {
        if (isStartVisible(frame) && now - lastTapMs >= TAP_REPEAT_MS) {
            tapNormalized(START_X, START_Y, displayWidth, displayHeight);
            state = State.START_SENT;
            lastTapMs = now;
            waitUntilMs = now + START_RESUME_DELAY_MS;
            Log.i(TAG, "start button detected");
            return;
        }

        if (isConfirmVisible(frame) && now - lastTapMs >= TAP_REPEAT_MS) {
            tapNormalized(CONFIRM_X, CONFIRM_Y, displayWidth, displayHeight);
            state = State.CONFIRM_SENT;
            lastTapMs = now;
            Log.i(TAG, "song confirm detected");
        }
    }

    private long detectionIntervalMs() {
        return state == State.IDLE ? IDLE_DETECTION_INTERVAL_MS : ACTIVE_DETECTION_INTERVAL_MS;
    }

    private void tapNormalized(double x, double y, int displayWidth, int displayHeight) {
        int tapX = (int) Math.round(x * displayWidth);
        int tapY = (int) Math.round(y * displayHeight);
        injector.down(tapX, tapY, TOUCH_ID);
        injector.up(TOUCH_ID);
    }

    private boolean isLiveClear(Bitmap frame) {
        return whiteRatio(frame, 0.16, 0.31, 0.72, 0.56) > 0.24
                && darkRatio(frame, 0.16, 0.31, 0.72, 0.56) > 0.50
                && whiteRatio(frame, 0.17, 0.34, 0.38, 0.54) > 0.25
                && whiteRatio(frame, 0.39, 0.32, 0.58, 0.54) > 0.25
                && whiteRatio(frame, 0.58, 0.32, 0.72, 0.54) > 0.25;
    }

    private boolean isConfirmVisible(Bitmap frame) {
        return cyanRatio(frame, 0.69, 0.78, 0.785, 0.855) > 0.08
                && darkRatio(frame, 0.69, 0.78, 0.785, 0.855) < 0.15;
    }

    private boolean isStartVisible(Bitmap frame) {
        return cyanRatio(frame, 0.725, 0.715, 0.755, 0.79) > 0.45
                && darkRatio(frame, 0.725, 0.715, 0.755, 0.79) < 0.08;
    }

    private double whiteRatio(Bitmap frame, double x1, double y1, double x2, double y2) {
        return ratio(frame, x1, y1, x2, y2, PixelTest.WHITE);
    }

    private double cyanRatio(Bitmap frame, double x1, double y1, double x2, double y2) {
        return ratio(frame, x1, y1, x2, y2, PixelTest.CYAN);
    }

    private double darkRatio(Bitmap frame, double x1, double y1, double x2, double y2) {
        return ratio(frame, x1, y1, x2, y2, PixelTest.DARK);
    }

    private double ratio(Bitmap frame, double x1, double y1, double x2, double y2, PixelTest test) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int left = clamp((int) Math.round(x1 * width), 0, width - 1);
        int top = clamp((int) Math.round(y1 * height), 0, height - 1);
        int right = clamp((int) Math.round(x2 * width), left + 1, width);
        int bottom = clamp((int) Math.round(y2 * height), top + 1, height);
        int step = Math.max(3, Math.min(width, height) / 80);
        int total = 0;
        int matched = 0;
        for (int y = top; y < bottom; y += step) {
            for (int x = left; x < right; x += step) {
                int color = frame.getPixel(x, y);
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                total++;
                if (test.matches(r, g, b)) {
                    matched++;
                }
            }
        }
        return total == 0 ? 0.0 : matched / (double) total;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private interface PixelTest {
        PixelTest WHITE = (r, g, b) -> r > 210 && g > 210 && b > 210;
        PixelTest CYAN = (r, g, b) -> g > 130 && b > 130 && r < 170;
        PixelTest DARK = (r, g, b) -> r < 95 && g < 95 && b < 130;

        boolean matches(int r, int g, int b);
    }

    private static final class State {
        static final int IDLE = 0;
        static final int GAME_ENDED = 1;
        static final int CONFIRM_SENT = 2;
        static final int START_SENT = 3;

        private State() {
        }
    }
}
