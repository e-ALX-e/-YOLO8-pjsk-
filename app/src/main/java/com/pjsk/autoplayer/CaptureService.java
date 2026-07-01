package com.pjsk.autoplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.pjsk.autoplayer.core.AutoContinueController;
import com.pjsk.autoplayer.core.AutoPlayer;
import com.pjsk.autoplayer.core.Detection;
import com.pjsk.autoplayer.input.RootEventInjector;
import com.pjsk.autoplayer.ncnn.NcnnDetector;
import com.pjsk.autoplayer.overlay.DetectionPreviewOverlay;
import com.pjsk.autoplayer.overlay.StatusOverlay;
import com.pjsk.autoplayer.screen.ScreenCaptureSource;
import com.pjsk.autoplayer.settings.AppSettings;
import com.pjsk.autoplayer.settings.DebugDisplayController;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CaptureService extends Service {
    // 外部通过这些 action 控制服务：开始录屏、停止服务、开关识别预览窗口。
    public static final String ACTION_START = "com.pjsk.autoplayer.START";
    public static final String ACTION_STOP = "com.pjsk.autoplayer.STOP";
    public static final String ACTION_SET_PREVIEW = "com.pjsk.autoplayer.SET_PREVIEW";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_PREVIEW_ENABLED = "previewEnabled";

    private static final String TAG = "PJSK-CaptureService";
    private static final String CHANNEL_ID = "pjsk_capture";
    private static final int NOTIFICATION_ID = 10;
    private static final long OVERLAY_UPDATE_INTERVAL_MS = 1000;
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 3000;
    private static final long FPS_WINDOW_MS = 1000;
    // 从“不点击模式”恢复到点击模式时，延迟 5 秒再真正允许点击。
    private static final long CLICK_RESUME_DELAY_MS = 5000;

    private static volatile boolean running;

    // 所有帧处理都串行放到单线程，避免识别、点击和 UI 更新同时抢状态。
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // processing 用来丢弃还没处理完时到来的新帧，防止排队导致延迟越来越大。
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final Object metricsLock = new Object();

    private ScreenCaptureSource captureSource;
    private NcnnDetector detector;
    private AutoPlayer autoPlayer;
    private AutoContinueController autoContinueController;
    private RootEventInjector injector;
    private StatusOverlay statusOverlay;
    private DetectionPreviewOverlay previewOverlay;

    // FPS 统计使用滑动窗口：只统计最近 1 秒内处理/丢弃的帧。
    private volatile int totalFrames;
    private volatile int totalDroppedFrames;
    private final Deque<Long> frameTimesMs = new ArrayDeque<>();
    private final Deque<Long> droppedTimesMs = new ArrayDeque<>();
    private volatile double currentFps;
    private volatile double currentDropFps;
    private volatile long lastInferenceMs;
    private final AtomicInteger totalActions = new AtomicInteger();
    private final AtomicInteger tapActions = new AtomicInteger();
    private final AtomicInteger holdActions = new AtomicInteger();
    private final AtomicInteger flickActions = new AtomicInteger();
    private volatile String lastActionText = "none";
    private long lastOverlayUpdateMs;
    private long lastNotificationUpdateMs;
    private long lastDiagnosticsLogMs;
    private long clickResumeAtMs;
    private boolean previousNoClickMode;
    private String detectorStatus = "";

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // 录屏服务必须以前台服务运行，否则系统可能停止 MediaProjection。
        Notification notification = buildNotification("等待录屏授权");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        // 停止服务：释放录屏、识别器、触摸注入器和悬浮窗。
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }

        // 只切换预览小窗口，不重启录屏。
        if (ACTION_SET_PREVIEW.equals(action)) {
            setPreviewEnabled(intent.getBooleanExtra(
                    EXTRA_PREVIEW_ENABLED,
                    AppSettings.isPreviewEnabled(this)));
            return running ? START_STICKY : START_NOT_STICKY;
        }

        // 开始录屏：使用 MainActivity 传入的录屏授权结果。
        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startCapture(resultCode, resultData);
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData) {
        // 没有授权数据就不能创建 MediaProjection。
        if (resultData == null) {
            Log.e(TAG, "missing MediaProjection result data");
            updateNotification("启动失败：缺少录屏授权");
            return;
        }

        stopEverything();
        running = true;
        AppSettings.ensureAutoContinueDefaultEnabled(this);

        // 启动核心组件：模型识别、root 点击、音符自动操作、自动继续状态机。
        detector = new NcnnDetector(this);
        detectorStatus = detector.status();
        Log.i(TAG, "detector status: " + detectorStatus);
        injector = new RootEventInjector(this);
        autoPlayer = new AutoPlayer(injector, this::recordAction);
        autoContinueController = new AutoContinueController(injector);
        resetCounters();
        previousNoClickMode = AppSettings.isNoClickMode(this);
        clickResumeAtMs = 0L;
        showOverlay("启动中\n模型：" + detectorStatus);
        setPreviewEnabled(AppSettings.isPreviewEnabled(this));

        // 通过系统服务拿到录屏投影。
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Log.e(TAG, "MediaProjectionManager is null");
            failStart("启动失败：无法获取录屏服务");
            return;
        }

        MediaProjection projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            Log.e(TAG, "MediaProjection is null");
            failStart("启动失败：录屏授权无效");
            return;
        }

        captureSource = new ScreenCaptureSource(this, projection, new ScreenCaptureSource.Listener() {
            @Override
            public boolean shouldCaptureFrame() {
                // 上一帧没处理完时直接丢帧，避免延迟堆积。
                if (processing.compareAndSet(false, true)) {
                    return true;
                }
                recordDroppedFrame();
                return false;
            }

            @Override
            public void onFrame(ScreenCaptureSource.Frame frame) {
                // 录屏回调线程只分发任务，真正处理放到 worker 单线程。
                worker.execute(() -> processFrame(frame));
            }

            @Override
            public void onCaptureError(Throwable error) {
                Log.e(TAG, "capture frame failed", error);
                recordDroppedFrame();
                processing.set(false);
            }
        });
        captureSource.start();

        updateVisibleStatus(formatStatus(0), true);
    }

    private void processFrame(ScreenCaptureSource.Frame frame) {
        long inferenceStartMs = SystemClock.elapsedRealtime();
        try {
            NcnnDetector currentDetector = detector;
            AutoPlayer currentAutoPlayer = autoPlayer;
            if (currentDetector == null || currentAutoPlayer == null) {
                return;
            }

            // 自动继续先看画面；如果正在接管流程，就暂停普通音符识别。
            long autoContinueStartMs = SystemClock.elapsedRealtime();
            runAutoContinue(frame);
            long autoContinueMs = Math.max(0L, SystemClock.elapsedRealtime() - autoContinueStartMs);
            if (isAutoContinueSuppressingGameRecognition()) {
                handleAutoContinueFrame(frame, inferenceStartMs, autoContinueMs);
                return;
            }

            // 普通演奏状态：运行 NCNN 模型识别音符。
            long detectStartMs = SystemClock.elapsedRealtime();
            List<Detection> detections = currentDetector.detect(frame.bitmap);
            long detectMs = Math.max(0L, SystemClock.elapsedRealtime() - detectStartMs);
            // 判定线位置和点击模式每帧读取设置，方便悬浮窗实时调整。
            double actionYBase = AppSettings.getActionY(this);
            currentAutoPlayer.setActionYBase(actionYBase);
            updateClickMode(currentAutoPlayer);
            long inferenceMs = Math.max(0L, SystemClock.elapsedRealtime() - inferenceStartMs);
            lastInferenceMs = inferenceMs;
            int detectionCount = detections.size();

            recordProcessedFrame();
            detectorStatus = currentDetector.status();
            long statusStartMs = SystemClock.elapsedRealtime();
            updateRuntimeStatus(detectionCount);
            long statusMs = Math.max(0L, SystemClock.elapsedRealtime() - statusStartMs);

            // 小预览窗口只显示识别情况，不参与点击决策。
            long previewStartMs = SystemClock.elapsedRealtime();
            updatePreview(frame, detections, inferenceMs, actionYBase);
            long previewMs = Math.max(0L, SystemClock.elapsedRealtime() - previewStartMs);

            // 根据模型识别结果执行 tap/hold/flick。
            long actionStartMs = SystemClock.elapsedRealtime();
            currentAutoPlayer.onFrame(
                    detections,
                    frame.width,
                    frame.height,
                    frame.displayWidth,
                    frame.displayHeight,
                    frame.timestampSec);
            long actionMs = Math.max(0L, SystemClock.elapsedRealtime() - actionStartMs);
            long totalMs = Math.max(0L, SystemClock.elapsedRealtime() - inferenceStartMs);

            long now = SystemClock.elapsedRealtime();
            if (now - lastDiagnosticsLogMs >= 1000) {
                // 每秒输出一次耗时日志，用来定位 FPS 瓶颈。
                lastDiagnosticsLogMs = now;
                Log.i(TAG, "frame=" + frame.width + "x" + frame.height
                        + " display=" + frame.displayWidth + "x" + frame.displayHeight
                        + " fps=" + String.format(Locale.US, "%.1f", currentFps)
                        + " infer=" + inferenceMs + "ms"
                        + " stageMs=capture:" + frame.captureMs
                        + ",detect:" + detectMs
                        + ",status:" + statusMs
                        + ",preview:" + previewMs
                        + ",autoContinue:" + autoContinueMs
                        + ",action:" + actionMs
                        + ",total:" + totalMs
                        + " drop/s=" + String.format(Locale.US, "%.1f", currentDropFps)
                        + " detections=" + detectionCount
                        + " actions=" + totalActions.get()
                        + " tap=" + tapActions.get()
                        + " hold=" + holdActions.get()
                        + " flick=" + flickActions.get()
                        + " actionY=" + String.format(Locale.US, "%.0f", actionYBase)
                        + " clickMode=" + clickModeText()
                        + " mapping=" + AppSettings.touchMappingLabel(
                        AppSettings.getTouchMappingMode(this))
                        + " detector=" + detectorStatus);
            }

        } catch (Throwable t) {
            Log.e(TAG, "process frame failed", t);
            updateVisibleStatus("处理异常：" + t.getClass().getSimpleName(), true);
        } finally {
            // 必须释放帧并清除 processing，否则后续帧会一直被丢弃。
            frame.close();
            processing.set(false);
        }
    }

    private void runAutoContinue(ScreenCaptureSource.Frame frame) {
        // 自动继续只依赖原始截图和显示尺寸，不依赖 NCNN 音符识别结果。
        AutoContinueController controller = autoContinueController;
        if (controller == null) {
            return;
        }
        controller.onFrame(
                frame.bitmap,
                frame.displayWidth,
                frame.displayHeight,
                AppSettings.isAutoContinueEnabled(this),
                isClickBlockedNow());
    }

    private boolean isAutoContinueSuppressingGameRecognition() {
        // 自动继续处于非 PLAYING 状态时，普通音符识别暂停。
        AutoContinueController controller = autoContinueController;
        return controller != null
                && AppSettings.isAutoContinueEnabled(this)
                && controller.shouldSuppressGameRecognition();
    }

    private void handleAutoContinueFrame(
            ScreenCaptureSource.Frame frame,
            long inferenceStartMs,
            long autoContinueMs) {
        // 自动继续接管时不跑模型，但仍更新 FPS、悬浮窗和预览。
        lastInferenceMs = autoContinueMs;
        recordProcessedFrame();
        long statusStartMs = SystemClock.elapsedRealtime();
        updateRuntimeStatus(0);
        long statusMs = Math.max(0L, SystemClock.elapsedRealtime() - statusStartMs);
        double actionYBase = AppSettings.getActionY(this);
        long previewStartMs = SystemClock.elapsedRealtime();
        updatePreview(frame, Collections.emptyList(), autoContinueMs, actionYBase);
        long previewMs = Math.max(0L, SystemClock.elapsedRealtime() - previewStartMs);
        long totalMs = Math.max(0L, SystemClock.elapsedRealtime() - inferenceStartMs);

        long now = SystemClock.elapsedRealtime();
        if (now - lastDiagnosticsLogMs >= 1000) {
            lastDiagnosticsLogMs = now;
            Log.i(TAG, "frame=" + frame.width + "x" + frame.height
                    + " display=" + frame.displayWidth + "x" + frame.displayHeight
                    + " fps=" + String.format(Locale.US, "%.1f", currentFps)
                    + " infer=" + autoContinueMs + "ms"
                    + " stageMs=capture:" + frame.captureMs
                    + ",detect:paused"
                    + ",status:" + statusMs
                    + ",preview:" + previewMs
                    + ",autoContinue:" + autoContinueMs
                    + ",action:paused"
                    + ",total:" + totalMs
                    + " drop/s=" + String.format(Locale.US, "%.1f", currentDropFps)
                    + " clickMode=" + clickModeText()
                    + " mapping=" + AppSettings.touchMappingLabel(
                    AppSettings.getTouchMappingMode(this))
                    + " detector=paused:autoContinue");
        }
    }

    private void resetCounters() {
        // 每次重新开始录屏时清空统计，避免旧数据污染当前 FPS。
        synchronized (metricsLock) {
            totalFrames = 0;
            totalDroppedFrames = 0;
            frameTimesMs.clear();
            droppedTimesMs.clear();
            currentFps = 0.0;
            currentDropFps = 0.0;
            lastInferenceMs = 0L;
        }
        lastOverlayUpdateMs = 0L;
        lastNotificationUpdateMs = 0L;
        lastDiagnosticsLogMs = 0L;
        clickResumeAtMs = 0L;
        totalActions.set(0);
        tapActions.set(0);
        holdActions.set(0);
        flickActions.set(0);
        lastActionText = "none";
    }

    private void recordProcessedFrame() {
        // 记录一帧处理完成，用于当前 FPS 和总帧数。
        long now = SystemClock.elapsedRealtime();
        synchronized (metricsLock) {
            totalFrames++;
            frameTimesMs.addLast(now);
            refreshFpsWindow(now);
        }
    }

    private void recordDroppedFrame() {
        // 处理不过来时记录丢帧，用 drop/s 判断性能瓶颈。
        long now = SystemClock.elapsedRealtime();
        synchronized (metricsLock) {
            totalDroppedFrames++;
            droppedTimesMs.addLast(now);
            refreshFpsWindow(now);
        }
    }

    private void recordAction(String action, int x, int y) {
        // AutoPlayer 每执行一次动作会回调这里，用来更新动作统计。
        totalActions.incrementAndGet();
        if ("tap".equals(action)) {
            tapActions.incrementAndGet();
        } else if ("hold".equals(action)) {
            holdActions.incrementAndGet();
        } else if ("flick".equals(action) || "hold_flick".equals(action)) {
            flickActions.incrementAndGet();
        }
        lastActionText = action + " " + x + "," + y;
    }

    private void refreshFpsWindow(long now) {
        // FPS 是最近 1 秒窗口内的帧数，不是累计平均值。
        trimWindow(frameTimesMs, now);
        trimWindow(droppedTimesMs, now);
        currentFps = frameTimesMs.size();
        currentDropFps = droppedTimesMs.size();
    }

    private void trimWindow(Deque<Long> timesMs, long now) {
        while (!timesMs.isEmpty() && now - timesMs.peekFirst() > FPS_WINDOW_MS) {
            timesMs.removeFirst();
        }
    }

    private void updateClickMode(AutoPlayer currentAutoPlayer) {
        // 不点击模式只识别不操作；关闭后延迟 5 秒恢复点击。
        boolean noClickMode = AppSettings.isNoClickMode(this);
        long now = SystemClock.elapsedRealtime();
        if (noClickMode) {
            clickResumeAtMs = 0L;
            currentAutoPlayer.setClickEnabled(false);
        } else {
            if (previousNoClickMode) {
                clickResumeAtMs = now + CLICK_RESUME_DELAY_MS;
            }
            currentAutoPlayer.setClickEnabled(clickResumeAtMs <= now);
        }
        previousNoClickMode = noClickMode;
    }

    private String clickModeText() {
        // 悬浮窗上的点击模式文本：只识别、延迟恢复中、或正常点击。
        if (AppSettings.isNoClickMode(this)) {
            return "只识别";
        }
        long remainingMs = clickResumeAtMs - SystemClock.elapsedRealtime();
        if (remainingMs > 0L) {
            long remainingSec = Math.max(1L, (remainingMs + 999L) / 1000L);
            return "延迟" + remainingSec + "s";
        }
        return "点击";
    }

    private boolean isClickBlockedNow() {
        // 自动继续和普通音符点击都会参考这个开关。
        return AppSettings.isNoClickMode(this)
                || clickResumeAtMs > SystemClock.elapsedRealtime();
    }

    private void updateRuntimeStatus(int detectionCount) {
        // 悬浮窗每秒更新一次，通知栏每 3 秒更新一次，降低 UI 开销。
        long now = SystemClock.elapsedRealtime();
        if (now - lastOverlayUpdateMs >= OVERLAY_UPDATE_INTERVAL_MS) {
            lastOverlayUpdateMs = now;
            updateVisibleStatus(formatStatus(detectionCount), false);
            if (statusOverlay != null) {
                statusOverlay.setNoClickMode(AppSettings.isNoClickMode(this));
                statusOverlay.setClickBlocked(isClickBlockedNow());
                statusOverlay.setAutoContinueStatus(autoContinueStatusText());
            }
        }

        if (now - lastNotificationUpdateMs >= NOTIFICATION_UPDATE_INTERVAL_MS) {
            lastNotificationUpdateMs = now;
            updateNotification(String.format(
                    Locale.US,
                    "运行中 FPS %.1f 识别 %d",
                    currentFps,
                    detectionCount));
        }
    }

    private String formatStatus(int detectionCount) {
        // 悬浮窗状态正文，包含 FPS、耗时、动作计数、判定线和模型状态。
        return String.format(
                Locale.US,
                "运行中\nFPS：%.1f  Drop/s：%.1f  Infer：%dms\nTotal：%d  DropTotal：%d  识别：%d\n点击：%s  动作：%d  Tap：%d  Hold：%d  Flick：%d\n判定：%.0f  映射：%s  最后：%s\n模型：%s",
                currentFps,
                currentDropFps,
                lastInferenceMs,
                totalFrames,
                totalDroppedFrames,
                detectionCount,
                clickModeText(),
                totalActions.get(),
                tapActions.get(),
                holdActions.get(),
                flickActions.get(),
                AppSettings.getActionY(this),
                AppSettings.touchMappingLabel(AppSettings.getTouchMappingMode(this)),
                lastActionText,
                detectorStatus);
    }

    private void updatePreview(
            ScreenCaptureSource.Frame frame,
            List<Detection> detections,
            long inferenceMs,
            double actionYBase) {
        // 预览窗口关闭或未显示时直接跳过，避免额外绘制影响 FPS。
        DetectionPreviewOverlay overlay = previewOverlay;
        if (overlay == null || !overlay.isShown()) {
            return;
        }
        overlay.updateFrame(
                frame.bitmap,
                detections,
                frame.width,
                frame.height,
                currentFps,
                inferenceMs,
                totalDroppedFrames,
                actionYBase);
    }

    private void failStart(String text) {
        // 启动失败时释放已创建组件，并把错误显示到悬浮窗/通知栏。
        releaseRuntime();
        running = false;
        updateVisibleStatus(text, true);
    }

    private void stopEverything() {
        // 用户停止服务时释放运行时和所有悬浮窗。
        releaseRuntime();
        running = false;
        if (previewOverlay != null) {
            previewOverlay.dismiss();
            previewOverlay = null;
        }
        if (statusOverlay != null) {
            statusOverlay.dismiss();
            statusOverlay = null;
        }
        updateNotification("已停止");
    }

    private void releaseRuntime() {
        // 释放录屏、root 注入器、识别器和状态机。
        if (captureSource != null) {
            captureSource.close();
            captureSource = null;
        }
        if (injector != null) {
            injector.shutdown();
            injector = null;
        }
        processing.set(false);
        autoPlayer = null;
        autoContinueController = null;
        detector = null;
        detectorStatus = "";
        resetCounters();
    }

    private void showOverlay(String text) {
        // 状态悬浮窗包含停止、预览、不点击模式、调试显示等按钮。
        if (statusOverlay == null) {
            statusOverlay = new StatusOverlay(this, () -> {
                stopEverything();
                stopSelf();
            }, () -> setPreviewEnabled(!AppSettings.isPreviewEnabled(this)),
                    this::toggleNoClickMode,
                    this::toggleDebugDisplay);
        }
        statusOverlay.show(text);
        statusOverlay.setPreviewEnabled(AppSettings.isPreviewEnabled(this));
        statusOverlay.setNoClickMode(AppSettings.isNoClickMode(this));
        statusOverlay.setClickBlocked(isClickBlockedNow());
        statusOverlay.setAutoContinueStatus(autoContinueStatusText());
        statusOverlay.setDebugDisplayEnabled(AppSettings.isDebugDisplayEnabled(this));
    }

    private String autoContinueStatusText() {
        // 小窗口右侧的自动继续状态文字来自 AutoContinueController。
        AutoContinueController controller = autoContinueController;
        if (controller == null || !AppSettings.isAutoContinueEnabled(this)) {
            return "演奏歌曲";
        }
        return controller.statusText();
    }

    private void toggleNoClickMode() {
        // 手动切换不点击模式；关闭时强制 5 秒延迟，给用户回到游戏的时间。
        boolean enabled = !AppSettings.isNoClickMode(this);
        AppSettings.setNoClickMode(this, enabled);
        if (enabled) {
            clickResumeAtMs = 0L;
        } else {
            clickResumeAtMs = SystemClock.elapsedRealtime() + CLICK_RESUME_DELAY_MS;
            previousNoClickMode = false;
        }
        if (statusOverlay != null) {
            statusOverlay.setNoClickMode(enabled);
            statusOverlay.setClickBlocked(true);
        }
        updateNotification(enabled ? "已开启不点击模式" : "5 秒后恢复点击");
    }

    private void toggleDebugDisplay() {
        // 调试显示会打开系统“显示点击操作/指针位置”，需要 root 写系统设置。
        boolean enabled = !AppSettings.isDebugDisplayEnabled(this);
        AppSettings.setDebugDisplayEnabled(this, enabled);
        if (statusOverlay != null) {
            statusOverlay.setDebugDisplayEnabled(enabled);
        }
        updateNotification(enabled ? "已开启调试显示" : "已关闭调试显示");
        new Thread(() -> {
            boolean ok = DebugDisplayController.setEnabled(enabled);
            if (!ok) {
                Log.w(TAG, "failed to apply debug display settings");
                updateNotification("调试显示设置失败，请检查 root 权限");
            }
        }, "pjsk-debug-display").start();
    }

    private void setPreviewEnabled(boolean enabled) {
        // 开关小预览窗口。关闭时销毁窗口，开启时需要悬浮窗权限。
        AppSettings.setPreviewEnabled(this, enabled);
        if (statusOverlay != null) {
            statusOverlay.setPreviewEnabled(enabled);
        }

        if (!enabled) {
            if (previewOverlay != null) {
                previewOverlay.dismiss();
                previewOverlay = null;
            }
            return;
        }

        if (!StatusOverlay.canDrawOverlays(this)) {
            updateNotification("预览需要开启悬浮窗权限");
            return;
        }

        if (previewOverlay == null) {
            previewOverlay = new DetectionPreviewOverlay(this, () -> setPreviewEnabled(false));
        }
        previewOverlay.show();
    }

    private void updateVisibleStatus(String text, boolean alsoNotification) {
        // 统一更新悬浮窗状态；必要时同步更新通知栏。
        if (statusOverlay == null || !statusOverlay.isShown()) {
            showOverlay(text);
        } else {
            statusOverlay.updateStatus(text);
        }
        if (alsoNotification) {
            updateNotification(text.replace('\n', ' '));
        }
    }

    private void updateNotification(String text) {
        // 前台服务通知，保证录屏服务持续运行。
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        // 通知点击回主界面，暂停按钮会发 ACTION_STOP 停止服务。
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class),
                pendingIntentFlags);

        PendingIntent stopIntent = PendingIntent.getService(
                this,
                2,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                pendingIntentFlags);

        return builder
                .setContentTitle("PJSK Native Auto")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
                .build();
    }

    private void createNotificationChannel() {
        // Android 8.0+ 必须先创建通知渠道才能显示前台服务通知。
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "PJSK Capture",
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        stopEverything();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
