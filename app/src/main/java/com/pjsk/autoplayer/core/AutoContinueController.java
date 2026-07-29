package com.pjsk.autoplayer.core;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.pjsk.autoplayer.input.TouchInjector;
import com.pjsk.autoplayer.ncnn.UiButtonDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AutoContinueController {
    private static final String TAG = "PJSK-AutoContinue";

    public static final String STATUS_PLAYING = "演奏乐曲";
    public static final String STATUS_GAME_ENDED = "游戏结束";
    public static final String STATUS_SELECT_SONG = "选择歌曲";

    private static final int TOUCH_ID = 9;

    private static final long PLAYING_DETECTION_INTERVAL_MS = 500;
    private static final long ACTIVE_DETECTION_INTERVAL_MS = 250;
    private static final long CONTINUE_TAP_REPEAT_MS = 500;
    private static final long PAGE_TAP_REPEAT_MS = 900;
    private static final long BUTTON_TAP_REPEAT_MS = 500;
    private static final long PLAY_WAIT_TIMEOUT_MS = 2000;
    private static final long PLAY_BUTTON_GONE_CONFIRM_MS = 2000;
    private static final long STARTING_SUPPRESS_MS = 2500;
    private static final long GAME_END_AFTER_START_GUARD_MS = 10000;

    private static final double RESULT_CONTINUE_X = 1700.0 / 1920.0;
    private static final double RESULT_CONTINUE_Y = 800.0 / 887.0;
    private static final double SOLO_LIVE_X = 1118.0 / 1920.0;
    private static final double SOLO_LIVE_Y = 305.0 / 887.0;

    private final TouchInjector injector;
    private final UiButtonDetector buttonDetector;

    private int state = State.PLAYING;
    private long lastDetectMs;
    private long lastTapMs;
    private long waitUntilMs;
    private long playWaitStartMs;
    private long liveClearBlockedUntilMs;
    private long lastNoteSeenMs;
    private long lastPlayButtonSeenMs;
    private List<Detection> lastButtonDetections = Collections.emptyList();

    public AutoContinueController(TouchInjector injector, UiButtonDetector buttonDetector) {
        this.injector = injector;
        this.buttonDetector = buttonDetector;
    }

    public void reset() {
        state = State.PLAYING;
        lastDetectMs = 0L;
        lastTapMs = 0L;
        waitUntilMs = 0L;
        playWaitStartMs = 0L;
        liveClearBlockedUntilMs = 0L;
        lastNoteSeenMs = 0L;
        lastPlayButtonSeenMs = 0L;
        lastButtonDetections = Collections.emptyList();
    }

    public boolean shouldSuppressGameRecognition() {
        return state != State.PLAYING;
    }

    public String statusText() {
        if (state == State.PLAYING || state == State.STARTING) {
            return STATUS_PLAYING;
        }
        if (state == State.GAME_ENDED) {
            return STATUS_GAME_ENDED;
        }
        return STATUS_SELECT_SONG;
    }

    public List<Detection> buttonDetectionsForPreview() {
        if (state != State.SELECT_SONG && state != State.WAIT_PLAY) {
            return Collections.emptyList();
        }
        return lastButtonDetections;
    }

    public void onFrame(
            Bitmap frame,
            int displayWidth,
            int displayHeight,
            boolean clickBlocked,
            List<Detection> noteDetections) {
        if (clickBlocked || frame == null || frame.isRecycled()) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if ((state == State.SELECT_SONG || state == State.WAIT_PLAY)
                && noteDetections != null && !noteDetections.isEmpty()) {
            lastNoteSeenMs = now;
        }
        if (now - lastDetectMs < detectionIntervalMs()) {
            return;
        }
        lastDetectMs = now;

        switch (state) {
            case State.PLAYING:
                lastButtonDetections = Collections.emptyList();
                detectGameEndOnly(frame, now);
                break;

            case State.GAME_ENDED:
                lastButtonDetections = Collections.emptyList();
                handleGameEnded(frame, displayWidth, displayHeight, now);
                break;

            case State.SELECT_SONG:
            case State.WAIT_PLAY:
                handleSongSelect(frame, displayWidth, displayHeight, now);
                break;

            case State.STARTING:
                if (now >= waitUntilMs) {
                    state = State.PLAYING;
                    lastTapMs = 0L;
                    waitUntilMs = 0L;
                    playWaitStartMs = 0L;
                    Log.i(TAG, "resumed playing after start guard");
                }
                break;
        }
    }

    private void detectGameEndOnly(Bitmap frame, long now) {
        if (now < liveClearBlockedUntilMs) {
            return;
        }
        if (isLiveClear(frame)) {
            state = State.GAME_ENDED;
            lastTapMs = 0L;
            playWaitStartMs = 0L;
            liveClearBlockedUntilMs = 0L;
            Log.i(TAG, "LIVE CLEAR detected");
        }
    }

    private void handleGameEnded(Bitmap frame, int displayWidth, int displayHeight, long now) {
        if (isSoloLiveVisible(frame)) {
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                tapNormalized("solo", SOLO_LIVE_X, SOLO_LIVE_Y, displayWidth, displayHeight);
                state = State.SELECT_SONG;
                lastTapMs = now;
                playWaitStartMs = 0L;
                Log.i(TAG, "solo live detected, switch to song selection");
            }
            return;
        }

        if (now - lastTapMs >= CONTINUE_TAP_REPEAT_MS) {
            tapNormalized("continue", RESULT_CONTINUE_X, RESULT_CONTINUE_Y, displayWidth, displayHeight);
            lastTapMs = now;
        }
    }

    private void handleSongSelect(Bitmap frame, int displayWidth, int displayHeight, long now) {
        List<Detection> buttons = buttonDetector.detect(frame);
        lastButtonDetections = buttons.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(buttons);
        Detection play = buttonDetector.findBest(buttons, UiButtonDetector.CLS_PLAY);

        if (play != null) {
            lastPlayButtonSeenMs = now;
            if (state == State.SELECT_SONG) {
                state = State.WAIT_PLAY;
                playWaitStartMs = now;
            }
            if (now - lastTapMs >= BUTTON_TAP_REPEAT_MS) {
                tapDetection("play", play, frame, displayWidth, displayHeight);
                lastTapMs = now;
                Log.i(TAG, "play button detected, tapped center");
            }
            return;
        }

        if (state == State.WAIT_PLAY) {
            if (lastPlayButtonSeenMs > 0L
                    && now - lastPlayButtonSeenMs >= PLAY_BUTTON_GONE_CONFIRM_MS) {
                state = State.STARTING;
                waitUntilMs = now + STARTING_SUPPRESS_MS;
                liveClearBlockedUntilMs = waitUntilMs + GAME_END_AFTER_START_GUARD_MS;
                playWaitStartMs = 0L;
                lastPlayButtonSeenMs = 0L;
                Log.i(TAG, "play button disappeared for 2s after tap, switching to playing");
            }
            return;
        }

        Detection confirm = buttonDetector.findBest(buttons, UiButtonDetector.CLS_CONFIRM);
        if (confirm != null && now - lastTapMs >= BUTTON_TAP_REPEAT_MS) {
            tapDetection("confirm", confirm, frame, displayWidth, displayHeight);
            state = State.WAIT_PLAY;
            lastTapMs = now;
            playWaitStartMs = now;
            lastPlayButtonSeenMs = 0L;
            Log.i(TAG, "confirm button detected, tapped center");
        }
    }

    private long detectionIntervalMs() {
        return state == State.PLAYING
                ? PLAYING_DETECTION_INTERVAL_MS
                : ACTIVE_DETECTION_INTERVAL_MS;
    }

    private void tapDetection(
            String reason,
            Detection detection,
            Bitmap frame,
            int displayWidth,
            int displayHeight) {
        int tapX = Math.round(detection.x / Math.max(1, frame.getWidth()) * displayWidth);
        float boxHeight = detection.y2 - detection.y1;
        float offsetY = detection.y - boxHeight * 0.15f;
        int tapY = Math.round(offsetY / Math.max(1, frame.getHeight()) * displayHeight);
        Log.i(TAG, "auto tap reason=" + reason
                + " x=" + tapX
                + " y=" + tapY
                + " score=" + detection.confidence
                + " state=" + state);
        injector.down(tapX, tapY, TOUCH_ID);
        injector.up(TOUCH_ID);
    }

    private void tapNormalized(String reason, double x, double y, int displayWidth, int displayHeight) {
        int tapX = (int) Math.round(x * displayWidth);
        int tapY = (int) Math.round(y * displayHeight);
        Log.i(TAG, "auto tap reason=" + reason + " x=" + tapX + " y=" + tapY + " state=" + state);
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

    private boolean isSoloLiveVisible(Bitmap frame) {
        return cyanRatio(frame, 0.505, 0.235, 0.655, 0.435) > 0.70
                && greenRatio(frame, 0.505, 0.235, 0.655, 0.435) > 0.65
                && darkRatio(frame, 0.505, 0.235, 0.655, 0.435) < 0.10;
    }

    private double whiteRatio(Bitmap frame, double x1, double y1, double x2, double y2) {
        return ratio(frame, x1, y1, x2, y2, PixelTest.WHITE);
    }

    private double cyanRatio(Bitmap frame, double x1, double y1, double x2, double y2) {
        return ratio(frame, x1, y1, x2, y2, PixelTest.CYAN);
    }

    private double greenRatio(Bitmap frame, double x1, double y1, double x2, double y2) {
        return ratio(frame, x1, y1, x2, y2, PixelTest.GREEN);
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
        PixelTest CYAN = (r, g, b) -> g > 130 && b > 130 && r < 200;
        PixelTest GREEN = (r, g, b) -> g > 150 && b > 120 && r < 190;
        PixelTest DARK = (r, g, b) -> r < 95 && g < 95 && b < 130;

        boolean matches(int r, int g, int b);
    }

    private static final class State {
        static final int PLAYING = 0;
        static final int GAME_ENDED = 1;
        static final int SELECT_SONG = 2;
        static final int WAIT_PLAY = 3;
        static final int STARTING = 4;

        private State() {
        }
    }
}
