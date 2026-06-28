package com.pjsk.autoplayer.core;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.pjsk.autoplayer.input.TouchInjector;

public final class AutoContinueController {
    private static final String TAG = "PJSK-AutoContinue";

    private static final int TOUCH_ID = 9;
    private static final long SELECT_TO_CONFIRM_DELAY_MS = 900;
    private static final long CONFIRM_REPEAT_MS = 1200;

    private static final double CONTINUE_X = 1595.0 / 1920.0;
    private static final double CONTINUE_Y = 772.0 / 887.0;
    private static final double SELECT_SONG_X = 1365.0 / 1920.0;
    private static final double SELECT_SONG_Y = 820.0 / 887.0;
    private static final double CONFIRM_X = 1415.0 / 1920.0;
    private static final double CONFIRM_Y = 725.0 / 887.0;

    private final TouchInjector injector;
    private int state = State.IDLE;
    private long lastTapMs;
    private long waitUntilMs;

    public AutoContinueController(TouchInjector injector) {
        this.injector = injector;
    }

    public void reset() {
        state = State.IDLE;
        lastTapMs = 0L;
        waitUntilMs = 0L;
    }

    public void onFrame(
            Bitmap frame,
            int displayWidth,
            int displayHeight,
            boolean enabled,
            boolean clickBlocked,
            int continueIntervalMs) {
        if (!enabled || clickBlocked || frame == null || frame.isRecycled()) {
            if (!enabled) {
                reset();
            }
            return;
        }

        long now = SystemClock.elapsedRealtime();
        switch (state) {
            case State.IDLE:
                if (isSelectSongVisible(frame)) {
                    tapNormalized(SELECT_SONG_X, SELECT_SONG_Y, displayWidth, displayHeight);
                    state = State.WAIT_SONG_PAGE;
                    waitUntilMs = now + SELECT_TO_CONFIRM_DELAY_MS;
                    lastTapMs = now;
                    Log.i(TAG, "select song detected from idle");
                } else if (isLiveClear(frame)) {
                    state = State.CLEAR_ADVANCING;
                    lastTapMs = 0L;
                    Log.i(TAG, "LIVE CLEAR detected");
                }
                break;

            case State.CLEAR_ADVANCING:
                if (isSelectSongVisible(frame)) {
                    tapNormalized(SELECT_SONG_X, SELECT_SONG_Y, displayWidth, displayHeight);
                    state = State.WAIT_SONG_PAGE;
                    waitUntilMs = now + SELECT_TO_CONFIRM_DELAY_MS;
                    lastTapMs = now;
                    Log.i(TAG, "select song detected");
                } else if (now - lastTapMs >= continueIntervalMs) {
                    tapNormalized(CONTINUE_X, CONTINUE_Y, displayWidth, displayHeight);
                    lastTapMs = now;
                }
                break;

            case State.WAIT_SONG_PAGE:
                if (now < waitUntilMs) {
                    return;
                }
                if (isConfirmVisible(frame)) {
                    tapNormalized(CONFIRM_X, CONFIRM_Y, displayWidth, displayHeight);
                    state = State.CONFIRM_SENT;
                    lastTapMs = now;
                    Log.i(TAG, "song confirm detected");
                }
                break;

            case State.CONFIRM_SENT:
                if (now - lastTapMs >= CONFIRM_REPEAT_MS && isConfirmVisible(frame)) {
                    tapNormalized(CONFIRM_X, CONFIRM_Y, displayWidth, displayHeight);
                    lastTapMs = now;
                }
                if (isLiveClear(frame)) {
                    state = State.CLEAR_ADVANCING;
                    lastTapMs = 0L;
                }
                break;
        }
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

    private boolean isSelectSongVisible(Bitmap frame) {
        return whiteRatio(frame, 0.655, 0.905, 0.762, 0.948) > 0.55
                && darkRatio(frame, 0.64, 0.885, 0.78, 0.965) > 0.20;
    }

    private boolean isConfirmVisible(Bitmap frame) {
        return cyanRatio(frame, 0.69, 0.78, 0.785, 0.855) > 0.08
                && darkRatio(frame, 0.69, 0.78, 0.785, 0.855) < 0.15;
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
        int step = Math.max(1, Math.min(width, height) / 240);
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
        static final int CLEAR_ADVANCING = 1;
        static final int WAIT_SONG_PAGE = 2;
        static final int CONFIRM_SENT = 3;

        private State() {
        }
    }
}
