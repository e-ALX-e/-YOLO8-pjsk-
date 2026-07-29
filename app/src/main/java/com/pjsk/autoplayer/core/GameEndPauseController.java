package com.pjsk.autoplayer.core;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

public final class GameEndPauseController {
    private static final String TAG = "PJSK-GameEndPause";
    private static final long DETECTION_INTERVAL_MS = 500;

    private long lastDetectMs;
    private boolean gameEnded;

    public void reset() {
        lastDetectMs = 0L;
        gameEnded = false;
    }

    public boolean isGameEnded() {
        return gameEnded;
    }

    public boolean onFrame(Bitmap frame) {
        if (gameEnded || frame == null || frame.isRecycled()) {
            return gameEnded;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastDetectMs < DETECTION_INTERVAL_MS) {
            return false;
        }
        lastDetectMs = now;

        if (isLiveClear(frame)) {
            gameEnded = true;
            Log.i(TAG, "LIVE CLEAR detected, pausing recognition and input");
        }
        return gameEnded;
    }

    private boolean isLiveClear(Bitmap frame) {
        return whiteRatio(frame, 0.16, 0.31, 0.72, 0.56) > 0.24
                && darkRatio(frame, 0.16, 0.31, 0.72, 0.56) > 0.50
                && whiteRatio(frame, 0.17, 0.34, 0.38, 0.54) > 0.25
                && whiteRatio(frame, 0.39, 0.32, 0.58, 0.54) > 0.25
                && whiteRatio(frame, 0.58, 0.32, 0.72, 0.54) > 0.25;
    }

    private double whiteRatio(Bitmap frame, double x1, double y1, double x2, double y2) {
        return ratio(frame, x1, y1, x2, y2, PixelTest.WHITE);
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
        PixelTest DARK = (r, g, b) -> r < 95 && g < 95 && b < 130;

        boolean matches(int r, int g, int b);
    }
}
