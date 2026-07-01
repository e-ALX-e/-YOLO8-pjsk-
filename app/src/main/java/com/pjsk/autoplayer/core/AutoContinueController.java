package com.pjsk.autoplayer.core;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.pjsk.autoplayer.input.TouchInjector;

public final class AutoContinueController {
    private static final String TAG = "PJSK-AutoContinue";

    private static final int TOUCH_ID = 9;

    // 演奏中只需要低频检查是否结算；自动继续流程中需要更快检查页面变化。
    private static final long PLAYING_DETECTION_INTERVAL_MS = 500;
    private static final long ACTIVE_DETECTION_INTERVAL_MS = 250;

    // 各页面按钮的重复点击间隔，避免同一个页面被高频连点。
    private static final long CONTINUE_TAP_REPEAT_MS = 500;
    private static final long PAGE_TAP_REPEAT_MS = 900;
    private static final long CONFIRM_TAP_REPEAT_MS = 500;

    // 进入选歌页后，等“确定”按钮稳定出现一小段时间再点击，避免动画中点空。
    private static final long CONFIRM_VISIBLE_STABLE_MS = 350;

    // 点击播放后先暂停自动继续流程；之后恢复音符识别，但继续屏蔽结算判定一段时间。
    private static final long STARTING_SUPPRESS_MS = 2500;
    private static final long GAME_END_AFTER_START_GUARD_MS = 10000;

    // 以下坐标都按 1920x887 的横屏截图归一化，实际点击时会按当前 display 宽高映射。
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

    // 记录选歌页第一次稳定出现的时间，用来延迟点击“确定”。
    private long songSelectVisibleSinceMs;

    // 点击播放后的保护时间，保护期内不允许 LIVE CLEAR 判定触发。
    private long liveClearBlockedUntilMs;
    private boolean soloLiveSeen;

    public AutoContinueController(TouchInjector injector) {
        this.injector = injector;
    }

    public void reset() {
        state = State.PLAYING;
        lastDetectMs = 0L;
        lastTapMs = 0L;
        waitUntilMs = 0L;
        songSelectVisibleSinceMs = 0L;
        liveClearBlockedUntilMs = 0L;
        soloLiveSeen = false;
    }

    public boolean shouldSuppressGameRecognition() {
        // 自动继续流程中暂停普通音符识别，让算力集中在页面识别和跳转上。
        return state != State.PLAYING;
    }

    public String statusText() {
        switch (state) {
            case State.GAME_ENDED:
                return "游戏结束";
            case State.WAIT_SONG_SELECT:
            case State.SELECT_SONG:
            case State.CONFIRMING_SONG:
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
            // 非演奏状态下，先根据当前画面做一次状态纠偏，防止上一帧状态落后。
            correctStateFromVisiblePage(frame);
        }
        if (now - lastDetectMs < detectionIntervalMs()) {
            return;
        }
        lastDetectMs = now;

        switch (state) {
            case State.PLAYING:
                detectGameEndOnly(frame, now);
                break;

            case State.GAME_ENDED:
                handleGameEnded(frame, displayWidth, displayHeight, now);
                break;

            case State.WAIT_SONG_SELECT:
            case State.SELECT_SONG:
            case State.CONFIRMING_SONG:
            case State.READY_TO_PLAY:
                handleSongStart(frame, displayWidth, displayHeight, now);
                break;

            case State.STARTING:
                if (now >= waitUntilMs) {
                    // 开始按钮点下后，到这里才恢复音符识别；结算判定仍由 liveClearBlockedUntilMs 保护。
                    state = State.PLAYING;
                    lastDetectMs = now;
                    lastTapMs = 0L;
                    waitUntilMs = 0L;
                    songSelectVisibleSinceMs = 0L;
                    soloLiveSeen = false;
                    Log.i(TAG, "game recognition resumed, live clear guarded");
                }
                break;
        }
    }

    private void detectGameEndOnly(Bitmap frame, long now) {
        // 开局保护期内不检测 LIVE CLEAR，避免加载/开场画面误判成游戏结束。
        if (now < liveClearBlockedUntilMs) {
            return;
        }
        if (isLiveClear(frame)) {
            state = State.GAME_ENDED;
            lastTapMs = 0L;
            liveClearBlockedUntilMs = 0L;
            soloLiveSeen = false;
            Log.i(TAG, "LIVE CLEAR detected");
        }
    }

    private void correctStateFromVisiblePage(Bitmap frame) {
        if (state == State.STARTING) {
            return;
        }

        // 游戏结束状态必须先经过单人演出入口，避免在结算页直接误跳选歌页。
        if (state == State.GAME_ENDED) {
            if (soloLiveSeen && isSongSelectVisible(frame)) {
                state = State.SELECT_SONG;
                songSelectVisibleSinceMs = 0L;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: song select");
            }
            return;
        }

        if (state == State.WAIT_SONG_SELECT
                || state == State.SELECT_SONG
                || state == State.CONFIRMING_SONG) {
            // 选歌流程中如果看见结算详情页，说明上一步没离开结算，退回游戏结束流程。
            if (isResultDetailVisible(frame)) {
                state = State.GAME_ENDED;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: result detail");
            } else if (isTeamStartPageVisible(frame)) {
                // 看见播放按钮页，说明“确定”已经生效，进入准备演奏阶段。
                state = State.READY_TO_PLAY;
                songSelectVisibleSinceMs = 0L;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: ready to play");
            }
            return;
        }

        if (state == State.READY_TO_PLAY) {
            // 准备演奏阶段也允许纠偏：如果回到结算或选歌页，就切回对应流程。
            if (isResultDetailVisible(frame)) {
                state = State.GAME_ENDED;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: result detail");
            } else if (isSongSelectVisible(frame)) {
                state = State.SELECT_SONG;
                songSelectVisibleSinceMs = 0L;
                lastTapMs = 0L;
                Log.i(TAG, "page correction: song select");
            }
        }
    }

    private void handleGameEnded(Bitmap frame, int displayWidth, int displayHeight, long now) {
        // 结算详情页优先点 OK/继续，直到进入主菜单或选歌相关页面。
        if (isResultDetailVisible(frame)) {
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                tapNormalized("result_ok", RESULT_CONTINUE_X, RESULT_CONTINUE_Y, displayWidth, displayHeight);
                lastTapMs = now;
            }
            return;
        }

        if (soloLiveSeen && isSongSelectVisible(frame)) {
            // 已经成功点击过单人演出后，才允许进入选歌状态。
            state = State.SELECT_SONG;
            songSelectVisibleSinceMs = now;
            lastTapMs = 0L;
            Log.i(TAG, "song select page detected");
            return;
        }

        if (isSoloLiveVisible(frame)) {
            // 主菜单的“单人演出”入口。
            soloLiveSeen = true;
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                tapNormalized("solo", SOLO_LIVE_X, SOLO_LIVE_Y, displayWidth, displayHeight);
                state = State.WAIT_SONG_SELECT;
                lastTapMs = now;
            }
            return;
        }

        if (now - lastTapMs >= CONTINUE_TAP_REPEAT_MS) {
            // LIVE CLEAR 初始页没有明确按钮时，持续点击右下角继续区域推进结算。
            tapNormalized("continue", RESULT_CONTINUE_X, RESULT_CONTINUE_Y, displayWidth, displayHeight);
            lastTapMs = now;
        }
    }

    private void handleSongStart(Bitmap frame, int displayWidth, int displayHeight, long now) {
        // 如果在选歌/准备流程中又看见结算详情页，立即退回游戏结束流程。
        if (isResultDetailVisible(frame)) {
            state = State.GAME_ENDED;
            lastTapMs = 0L;
            return;
        }

        if (state == State.WAIT_SONG_SELECT && isSoloLiveVisible(frame)) {
            // 点击单人演出后如果页面没跳走，按间隔重试。
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                tapNormalized("solo_retry", SOLO_LIVE_X, SOLO_LIVE_Y, displayWidth, displayHeight);
                lastTapMs = now;
            }
            return;
        }

        if (isTeamStartPageVisible(frame)) {
            // 识别到播放按钮页，等待播放按钮可点击。
            state = State.READY_TO_PLAY;
            songSelectVisibleSinceMs = 0L;
        }

        if (isStartVisible(frame)) {
            // 播放按钮出现后点击，随后进入 STARTING，短时间内不做页面自动继续。
            if (now - lastTapMs >= PAGE_TAP_REPEAT_MS) {
                tapNormalized("start", START_X, START_Y, displayWidth, displayHeight);
                state = State.STARTING;
                lastTapMs = now;
                waitUntilMs = now + STARTING_SUPPRESS_MS;
                liveClearBlockedUntilMs = waitUntilMs + GAME_END_AFTER_START_GUARD_MS;
                Log.i(TAG, "start button detected");
            }
            return;
        }

        boolean songSelectVisible = isSongSelectVisible(frame);
        boolean confirmVisible = isConfirmVisible(frame);
        if (songSelectVisible) {
            // 选歌页刚出现时记录时间，后面用来等待“确定”按钮稳定。
            if (state != State.SELECT_SONG && state != State.CONFIRMING_SONG) {
                songSelectVisibleSinceMs = now;
            } else if (songSelectVisibleSinceMs == 0L) {
                songSelectVisibleSinceMs = now;
            }
            if (state != State.CONFIRMING_SONG) {
                state = State.SELECT_SONG;
            }
        } else if ((state == State.SELECT_SONG || state == State.CONFIRMING_SONG)
                && confirmVisible
                && songSelectVisibleSinceMs == 0L) {
            // 页面整体识别偶尔抖动时，如果确定按钮可见，也可以开始稳定计时。
            songSelectVisibleSinceMs = now;
        }

        if ((state == State.SELECT_SONG || state == State.CONFIRMING_SONG)
                && (songSelectVisible || confirmVisible)
                && now - songSelectVisibleSinceMs >= CONFIRM_VISIBLE_STABLE_MS
                && confirmVisible) {
            // 等确定按钮稳定出现后再点击；如果点击后没跳走，会按 CONFIRM_TAP_REPEAT_MS 重试。
            if (now - lastTapMs >= CONFIRM_TAP_REPEAT_MS) {
                tapNormalized("confirm", CONFIRM_X, CONFIRM_Y, displayWidth, displayHeight);
                state = State.CONFIRMING_SONG;
                lastTapMs = now;
                Log.i(TAG, "confirm button visible, tapping confirm");
            }
        }
    }

    private long detectionIntervalMs() {
        // 演奏中降低结算判定频率；自动继续过程中提高页面检测频率。
        return state == State.PLAYING
                ? PLAYING_DETECTION_INTERVAL_MS
                : ACTIVE_DETECTION_INTERVAL_MS;
    }

    private void tapNormalized(String reason, double x, double y, int displayWidth, int displayHeight) {
        // 保护：正常演奏状态不允许自动继续逻辑发点击，避免干扰音符操作。
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
        // LIVE CLEAR 判定：检查中间大字区域的白色和暗色比例。
        return whiteRatio(frame, 0.16, 0.31, 0.72, 0.56) > 0.24
                && darkRatio(frame, 0.16, 0.31, 0.72, 0.56) > 0.50
                && whiteRatio(frame, 0.17, 0.34, 0.38, 0.54) > 0.25
                && whiteRatio(frame, 0.39, 0.32, 0.58, 0.54) > 0.25
                && whiteRatio(frame, 0.58, 0.32, 0.72, 0.54) > 0.25;
    }

    private boolean isSoloLiveVisible(Bitmap frame) {
        // 主菜单“单人演出”卡片：主要依赖青绿色卡片区域。
        return cyanRatio(frame, 0.505, 0.235, 0.655, 0.435) > 0.70
                && greenRatio(frame, 0.505, 0.235, 0.655, 0.435) > 0.65
                && darkRatio(frame, 0.505, 0.235, 0.655, 0.435) < 0.10;
    }

    private boolean isSongSelectVisible(Bitmap frame) {
        // 选歌页判定：顶部搜索框、右侧手机面板、底部图标区域，并排除播放按钮页。
        return (whiteRatio(frame, 0.14, 0.03, 0.46, 0.09) > 0.45
                    || darkRatio(frame, 0.67, 0.875, 0.79, 0.955) > 0.40)
                && darkRatio(frame, 0.58, 0.08, 0.90, 0.95) > 0.45
                && greenRatio(frame, 0.724, 0.718, 0.763, 0.795) < 0.22
                && darkRatio(frame, 0.67, 0.875, 0.79, 0.955) > 0.35;
    }

    private boolean isConfirmVisible(Bitmap frame) {
        // 选歌页“确定”按钮判定：按钮边框/填充区域的青绿和暗色比例。
        return cyanRatio(frame, 0.685, 0.765, 0.79, 0.86) > 0.08
                && greenRatio(frame, 0.685, 0.765, 0.79, 0.86) > 0.06
                && darkRatio(frame, 0.685, 0.765, 0.79, 0.86) < 0.45;
    }

    private boolean isResultDetailVisible(Bitmap frame) {
        // 结算详情页判定：右下角“选择歌曲/OK”区域存在，且顶部搜索框不存在。
        return whiteRatio(frame, 0.65, 0.895, 0.77, 0.955) > 0.45
                && cyanRatio(frame, 0.78, 0.895, 0.92, 0.965) > 0.65
                && whiteRatio(frame, 0.14, 0.03, 0.46, 0.09) < 0.20;
    }

    private boolean isTeamStartPageVisible(Bitmap frame) {
        // 编队/准备页判定：右侧手机面板存在，并且播放按钮区域偏绿色。
        return darkRatio(frame, 0.58, 0.08, 0.90, 0.95) > 0.45
                && greenRatio(frame, 0.724, 0.718, 0.763, 0.795) > 0.25
                && darkRatio(frame, 0.505, 0.235, 0.655, 0.435) > 0.25;
    }

    private boolean isStartVisible(Bitmap frame) {
        // 播放按钮判定：在准备页基础上进一步确认播放三角按钮。
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
        // 在归一化矩形区域内抽样统计颜色比例。step 越大越快，但越容易漏掉小元素。
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
        // 简单 RGB 阈值分类，页面识别全部基于这些颜色比例。
        PixelTest WHITE = (r, g, b) -> r > 210 && g > 210 && b > 210;
        PixelTest CYAN = (r, g, b) -> g > 130 && b > 130 && r < 200;
        PixelTest GREEN = (r, g, b) -> g > 150 && b > 120 && r < 190;
        PixelTest DARK = (r, g, b) -> r < 95 && g < 95 && b < 130;

        boolean matches(int r, int g, int b);
    }

    private static final class State {
        // 自动继续状态机：
        // PLAYING：演奏中，只低频检测游戏结束。
        // GAME_ENDED：检测到 LIVE CLEAR 后推进结算页。
        // WAIT_SONG_SELECT：已点击单人演出，等待进入选歌页。
        // SELECT_SONG：已识别选歌页，等待确定按钮稳定出现。
        // CONFIRMING_SONG：已点击确定，若仍停在选歌页则继续重试。
        // READY_TO_PLAY：已进入播放按钮页，等待点击播放。
        // STARTING：播放已点击，短暂保护后恢复演奏识别。
        static final int PLAYING = 0;
        static final int GAME_ENDED = 1;
        static final int WAIT_SONG_SELECT = 2;
        static final int SELECT_SONG = 3;
        static final int CONFIRMING_SONG = 4;
        static final int READY_TO_PLAY = 5;
        static final int STARTING = 6;

        private State() {
        }
    }
}
