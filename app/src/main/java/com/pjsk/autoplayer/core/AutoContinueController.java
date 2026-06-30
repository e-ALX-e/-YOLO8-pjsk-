package com.pjsk.autoplayer.core;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.pjsk.autoplayer.input.TouchInjector;

public final class AutoContinueController {
    private static final String TAG = "PJSK-AutoContinue";

    private static final int TOUCH_ID = 9;
    private static final long PLAYING_DETECTION_INTERVAL_MS = 500;
    private static final long ACTIVE_DETECTION_INTERVAL_MS = 250;
    private static final long CONTINUE_TAP_REPEAT_MS = 500;
    private static final long PAGE_TAP_REPEAT_MS = 900;
    private static final long STARTING_SUPPRESS_MS = 2500;

    private static final double RESULT_CONTINUE_X = 1700.0 / 1920.0;
    private static final double RESULT_CONTINUE_Y = 800.0 / 887.0;
    private static final double SOLO_LIVE_X = 1118.0 / 1920.0;
    private static final double SOLO_LIVE_Y = 305.0 / 887.0;
    private static final double CONFIRM_X = 1415.0 / 1920.0;
    private static final double CONFIRM_Y = 725.0 / 887.0;
    private static final double START_X = 1415.0 / 1920.0;
    private static final double START_Y = 675.0 / 887.0;

    private final TouchInjector injector;
    private int state = State.PLAYING;
    private long lastDetectMs;
    private long lastTapMs;
    private long waitUntilMs;
    private boolean soloLiveSeen;

    public AutoContinueController(TouchInjector injector) {
        this.injector = injector;
    }

    public void reset() {
        state = State.PLAYING;
        lastDetectMs = 0L;
        lastTapMs = 0L;
        waitUntilMs = 0L;
        soloLiveSeen = false;
    }

    public boolean shouldSuppressGameRecognition() {
        return state != State.PLAYING;
    }

    public String statusText() {
        switch (state) {
            case State.GAME_ENDED:
                return "游戏结束";
            case State.WAIT_SONG_SELECT:
            case State.SELECT_SONG:
                return "选择歌曲";
            case State.READY_TO_PLAY:
            case State.STARTING:
                return "准备演奏";
            case State.PLAYING:
            default:
                return "演奏歌曲";
        }
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
        if (state != State.PLAYING) {
            correctStateFromVisiblePage(frame);
        }
        if (now - lastDetectMs < detectionIntervalMs()) {
            return;
        }
        lastDetectMs = now;

        switch (state) {
            case State.PLAYING:
                detectGameEndOnly(frame);
                break;

            case State.GAME_ENDED:
                handleGameEnded(frame, displayWidth, displayHeight, now);
                break;

            case State.WAIT_SONG_SELECT:
            case State.SELECT_SONG:
            case State.READY_TO_PLAY:
                handleSongStart(frame, displayWidth, displayHeight, now);
                break;

            case State.STARTING:
                if (now >= waitUntilMs) {
                    reset();
                    Log.i(TAG, "game recognition resumed");
                }
                break;
        }
    }

    private void detectGameEndOnly(Bitmap frame) {
        if (isLiveClear(frame)) {
            state = State.GAME_ENDED;
            lastTapMs = 0L;
            soloLiveSeen = false;
            Log.i(TAG, "LIVE CLEAR detected");
        }
    }

    private void correctStateFromVisiblePage(Bitmap frame) {
        if (state == State.STARTING) {
            return;
        }

        if (state == State.GAME_ENDED) {
            if (soloLiveSeen && isSongSelectVisible(frame)) {
                state = State.SELECT_SONG;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: song select");
            }
            return;
        }

        if (state == State.WAIT_SONG_SELECT || state == State.SELECT_SONG) {
            if (isResultDetailVisible(frame)) {
                state = State.GAME_ENDED;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: result detail");
            } else if (isTeamStartPageVisible(frame)) {
                state = State.READY_TO_PLAY;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: ready to play");
            }
            return;
        }

        if (state == State.READY_TO_PLAY) {
            if (isResultDetailVisible(frame)) {
                state = State.GAME_ENDED;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: result detail");
            } else if (isSongSelectVisible(frame)) {
                state = State.SELECT_SONG;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: song select");
            }
        }
    }

    private void handleGameEnded(Bitmap frame, int displayWidth, int displayHeight, long now) {
        if (isResultDetailVisible(frame)) {
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                tapNormalized("result_ok", RESULT_CONTINUE_X, RESULT_CONTINUE_Y, displayWidth, displayHeight);
                lastTapMs = now;
            }
            return;
        }

        if (soloLiveSeen && isSongSelectVisible(frame)) {
            state = State.SELECT_SONG;
            lastTapMs = 0L;
            Log.i(TAG, "song select page detected");
            return;
        }

        if (isSoloLiveVisible(frame)) {
            soloLiveSeen = true;
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                tapNormalized("solo", SOLO_LIVE_X, SOLO_LIVE_Y, displayWidth, displayHeight);
                state = State.WAIT_SONG_SELECT;
                lastTapMs = now;
            }
            return;
        }

        if (now - lastTapMs >= CONTINUE_TAP_REPEAT_MS) {
            tapNormalized("continue", RESULT_CONTINUE_X, RESULT_CONTINUE_Y, displayWidth, displayHeight);
            lastTapMs = now;
        }
    }

    private void handleSongStart(Bitmap frame, int displayWidth, int displayHeight, long now) {
        if (isResultDetailVisible(frame)) {
            state = State.GAME_ENDED;
            lastTapMs = 0L;
            return;
        }

        if (state == State.WAIT_SONG_SELECT && isSoloLiveVisible(frame)) {
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                tapNormalized("solo_retry", SOLO_LIVE_X, SOLO_LIVE_Y, displayWidth, displayHeight);
                lastTapMs = now;
            }
            return;
        }

        if (isTeamStartPageVisible(frame)) {
            state = State.READY_TO_PLAY;
        }

        if (isStartVisible(frame)) {
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                tapNormalized("start", START_X, START_Y, displayWidth, displayHeight);
                state = State.STARTING;
                lastTapMs = now;
                waitUntilMs = now + STARTING_SUPPRESS_MS;
                Log.i(TAG, "start button detected");
            }
            return;
        }

        if (isSongSelectVisible(frame)) {
            state = State.SELECT_SONG;
        }

        if (state == State.SELECT_SONG) {
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                if (isConfirmVisible(frame)) {
                    tapNormalized("confirm", CONFIRM_X, CONFIRM_Y, displayWidth, displayHeight);
                    state = State.READY_TO_PLAY;
                    lastTapMs = now;
                    Log.i(TAG, "song confirm detected");
                }
            }
        }
    }

    private long detectionIntervalMs() {
        return state == State.PLAYING
                ? PLAYING_DETECTION_INTERVAL_MS
                : ACTIVE_DETECTION_INTERVAL_MS;
    }

    private void tapNormalized(String reason, double x, double y, int displayWidth, int displayHeight) {
        if (state == State.PLAYING) {
            Log.w(TAG, "blocked tap while playing reason=" + reason);
            return;
        }
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

    private boolean isSongSelectVisible(Bitmap frame) {
        return whiteRatio(frame, 0.14, 0.03, 0.46, 0.09) > 0.50
                && darkRatio(frame, 0.58, 0.08, 0.90, 0.95) > 0.45
                && greenRatio(frame, 0.724, 0.718, 0.763, 0.795) < 0.22
                && darkRatio(frame, 0.67, 0.875, 0.79, 0.955) > 0.40;
    }

    private boolean isConfirmVisible(Bitmap frame) {
        return cyanRatio(frame, 0.685, 0.765, 0.79, 0.86) > 0.08
                && greenRatio(frame, 0.685, 0.765, 0.79, 0.86) > 0.06
                && darkRatio(frame, 0.685, 0.765, 0.79, 0.86) < 0.45;
    }

    private boolean isResultDetailVisible(Bitmap frame) {
        return whiteRatio(frame, 0.65, 0.895, 0.77, 0.955) > 0.45
                && cyanRatio(frame, 0.78, 0.895, 0.92, 0.965) > 0.65
                && whiteRatio(frame, 0.14, 0.03, 0.46, 0.09) < 0.20;
    }

    private boolean isTeamStartPageVisible(Bitmap frame) {
        return darkRatio(frame, 0.58, 0.08, 0.90, 0.95) > 0.45
                && greenRatio(frame, 0.724, 0.718, 0.763, 0.795) > 0.25
                && darkRatio(frame, 0.505, 0.235, 0.655, 0.435) > 0.25;
    }

    private boolean isStartVisible(Bitmap frame) {
        return isTeamStartPageVisible(frame)
                && greenRatio(frame, 0.724, 0.718, 0.763, 0.795) > 0.25
                && darkRatio(frame, 0.724, 0.718, 0.763, 0.795) < 0.08;
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
        static final int WAIT_SONG_SELECT = 2;
        static final int SELECT_SONG = 3;
        static final int READY_TO_PLAY = 4;
        static final int STARTING = 5;

        private State() {
        }
    }
}
