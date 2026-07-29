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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.pjsk.autoplayer.core.AutoContinueController;
import com.pjsk.autoplayer.core.AutoPlayer;
import com.pjsk.autoplayer.core.Detection;
import com.pjsk.autoplayer.input.RootEventInjector;
import com.pjsk.autoplayer.ncnn.NcnnDetector;
import com.pjsk.autoplayer.ncnn.UiButtonDetector;
import com.pjsk.autoplayer.overlay.DetectionPreviewOverlay;
import com.pjsk.autoplayer.overlay.StatusOverlay;
import com.pjsk.autoplayer.screen.ScreenCaptureSource;
import com.pjsk.autoplayer.screen.RootScreenRecorder;
import com.pjsk.autoplayer.settings.AppSettings;
import com.pjsk.autoplayer.settings.DebugDisplayController;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CaptureService extends Service {
    public static final String ACTION_START = "com.pjsk.autoplayer.START";
    public static final String ACTION_STOP = "com.pjsk.autoplayer.STOP";
    public static final String ACTION_SET_PREVIEW = "com.pjsk.autoplayer.SET_PREVIEW";
    public static final String ACTION_REFRESH_OVERLAY = "com.pjsk.autoplayer.REFRESH_OVERLAY";
    public static final String ACTION_TOGGLE_OVERLAY_PARAMETERS = "com.pjsk.autoplayer.TOGGLE_OVERLAY_PARAMETERS";
    public static final String ACTION_RESET_PLAYBACK = "com.pjsk.autoplayer.RESET_PLAYBACK";
    public static final String ACTION_TOGGLE_DEBUG_DISPLAY = "com.pjsk.autoplayer.TOGGLE_DEBUG_DISPLAY";
    public static final String ACTION_TOGGLE_SCREEN_RECORDING = "com.pjsk.autoplayer.TOGGLE_SCREEN_RECORDING";
    public static final String ACTION_TOGGLE_OVERLAY_VISIBILITY = "com.pjsk.autoplayer.TOGGLE_OVERLAY_VISIBILITY";
    public static final String ACTION_TOGGLE_OVERLAY_COLLAPSE = "com.pjsk.autoplayer.TOGGLE_OVERLAY_COLLAPSE";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_PREVIEW_ENABLED = "previewEnabled";
    public static final String EXTRA_LAUNCH_CAPTURE_TARGET = "launchCaptureTarget";

    private static final String TAG = "PJSK-CaptureService";
    private static final String CHANNEL_ID = "pjsk_capture";
    private static final int NOTIFICATION_ID = 10;
    private static final long OVERLAY_UPDATE_INTERVAL_MS = 1000;
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 3000;
    private static final long FPS_WINDOW_MS = 1000;
    private static final long CLICK_RESUME_DELAY_MS = 5000;
    private static final long LOGIC_CONFIG_REFRESH_MS = 1000;
    // Near-120 Hz motion updates keep long-note movement independent of capture FPS.
    private static final long LOGIC_TIMELINE_TICK_MS = 8;

    private static volatile boolean running;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService startupWorker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService logicTimelineWorker = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean logicTimelineFinishPending = new AtomicBoolean(false);
    private final AtomicBoolean projectionRecoveryPending = new AtomicBoolean(false);
    private final AtomicBoolean captureTargetLaunchPending = new AtomicBoolean(false);
    private final AtomicBoolean hiddenKeyMonitorRunning = new AtomicBoolean(false);
    private final AtomicBoolean screenRecordingTogglePending = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object metricsLock = new Object();

    private ScreenCaptureSource captureSource;
    private NcnnDetector detector;
    private UiButtonDetector uiButtonDetector;
    private volatile AutoPlayer autoPlayer;
    private AutoContinueController autoContinueController;
    private RootEventInjector injector;
    private StatusOverlay statusOverlay;
    private DetectionPreviewOverlay previewOverlay;
    private final RootScreenRecorder screenRecorder = new RootScreenRecorder();
    private volatile Process hiddenKeyMonitorProcess;
    private volatile boolean overlaysHidden;

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
    private long lastLogicConfigRefreshMs = Long.MIN_VALUE;
    private long clickResumeAtMs;
    private boolean previousNoClickMode;
    private boolean previousLogicPlayMode;
    private boolean cachedLogicPlaySettingEnabled;
    private boolean cachedLogicPlayEnabled;
    private int cachedLogicTapIntervalMs = 500;
    private double cachedLogicTapXRatio = 0.5;
    private List<AutoPlayer.LogicEvent> cachedLogicEvents = Collections.emptyList();
    private String detectorStatus = "";
    private String autoContinueStatus = AutoContinueController.STATUS_PLAYING;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logicTimelineWorker.scheduleAtFixedRate(
                this::advanceLogicTimeline,
                0L,
                LOGIC_TIMELINE_TICK_MS,
                TimeUnit.MILLISECONDS);
        createNotificationChannel();
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
        if (ACTION_STOP.equals(action)) {
            stopAndTerminateApp();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_PREVIEW.equals(action)) {
            setPreviewEnabled(intent.getBooleanExtra(
                    EXTRA_PREVIEW_ENABLED,
                    AppSettings.isPreviewEnabled(this)));
            return running ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_REFRESH_OVERLAY.equals(action)) {
            refreshOverlayCustomButton();
            return running ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_OVERLAY_PARAMETERS.equals(action)) {
            boolean visible = !AppSettings.isOverlayParametersVisible(this);
            AppSettings.setOverlayParametersVisible(this, visible);
            if (statusOverlay != null) {
                statusOverlay.applyLayoutPreferences(
                        AppSettings.isOverlayCollapsed(this), visible);
            }
            return running ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_RESET_PLAYBACK.equals(action)) {
            resetPlaybackState();
            return running ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_DEBUG_DISPLAY.equals(action)) {
            toggleDebugDisplay();
            return running ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_SCREEN_RECORDING.equals(action)) {
            toggleScreenRecording();
            return running ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_OVERLAY_VISIBILITY.equals(action)) {
            if (!running) {
                AppSettings.setOverlayHidden(this, !AppSettings.isOverlayHidden(this));
                return START_NOT_STICKY;
            }
            if (overlaysHidden) {
                restoreHiddenOverlays();
            } else {
                hideOverlays();
            }
            return running ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_TOGGLE_OVERLAY_COLLAPSE.equals(action)) {
            boolean collapsed = !AppSettings.isOverlayCollapsed(this);
            AppSettings.setOverlayCollapsed(this, collapsed);
            if (statusOverlay != null) {
                statusOverlay.applyLayoutPreferences(
                        collapsed, AppSettings.isOverlayParametersVisible(this));
            }
            return running ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            boolean launchCaptureTarget = intent.getBooleanExtra(
                    EXTRA_LAUNCH_CAPTURE_TARGET,
                    false);
            // NCNN model creation can take several seconds. Never do it from the service main
            // thread, otherwise the activity sharing this process will receive an input ANR.
            updateNotification("\u6b63\u5728\u521d\u59cb\u5316\u6a21\u578b");
            startupWorker.execute(() -> startCapture(resultCode, resultData, launchCaptureTarget));
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData, boolean launchCaptureTarget) {
        if (resultData == null) {
            Log.e(TAG, "missing MediaProjection result data");
            updateNotification("启动失败：缺少录屏授权");
            return;
        }

        stopEverything();
        captureTargetLaunchPending.set(launchCaptureTarget);
        running = true;
        projectionRecoveryPending.set(false);

        detector = new NcnnDetector(this);
        detectorStatus = detector.status();
        Log.i(TAG, "detector status: " + detectorStatus);
        injector = new RootEventInjector(this);
        autoPlayer = new AutoPlayer(injector, this::recordAction);
        updateAutoSoloRuntime(shouldRunAutoContinue());
        resetCounters();
        overlaysHidden = AppSettings.isOverlayHidden(this);
        previousLogicPlayMode = AppSettings.isLogicPlayModeEnabled(this);
        if (previousLogicPlayMode) {
            forceLogicPlayWaitLoading(autoPlayer);
        }
        previousNoClickMode = AppSettings.isNoClickMode(this);
        clickResumeAtMs = 0L;
        if (overlaysHidden) {
            startHiddenKeyMonitor();
        }
        showOverlay("启动中\n模型：" + detectorStatus);
        setPreviewEnabled(AppSettings.isPreviewEnabled(this));
        applyDebugDisplaySetting();

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
                AutoPlayer currentAutoPlayer = autoPlayer;
                // Logic play has its own monotonic-clock scheduler.  Keep the
                // MediaProjection alive, but skip bitmap conversion, inference,
                // preview drawing, and worker dispatch until the timeline ends.
                if (currentAutoPlayer != null && currentAutoPlayer.isLogicPlayActive()) {
                    return false;
                }
                if (processing.compareAndSet(false, true)) {
                    return true;
                }
                recordDroppedFrame();
                return false;
            }

            @Override
            public void onFrame(ScreenCaptureSource.Frame frame) {
                worker.execute(() -> processFrame(frame));
            }

            @Override
            public void onCaptureError(Throwable error) {
                Log.e(TAG, "capture frame failed", error);
                recordDroppedFrame();
                processing.set(false);
            }

            @Override
            public void onCaptureStopped() {
                if (projectionRecoveryPending.compareAndSet(false, true)) {
                    processing.set(false);
                    AutoPlayer player = autoPlayer;
                    if (player != null) {
                        player.setClickEnabled(false);
                    }
                    worker.execute(CaptureService.this::recoverStoppedProjection);
                }
            }
        });
        captureSource.start();
        launchCaptureTargetAfterDisplayReady();

        updateVisibleStatus(formatStatus(0), true);
    }

    /**
     * A single-app projection may not emit a frame until its selected game is foreground.
     * Waiting for the first frame would therefore deadlock at a black preview. Returning
     * from createVirtualDisplay confirms the capture surface is ready, while the overlay
     * was already shown above; that is the safe point to open the selected game.
     */
    private void launchCaptureTargetAfterDisplayReady() {
        if (!captureTargetLaunchPending.compareAndSet(true, false)) {
            return;
        }
        mainHandler.postDelayed(() -> {
            if (!running || projectionRecoveryPending.get()) {
                return;
            }
            String packageName = AppSettings.captureTargetPackage(this);
            if (packageName.isEmpty()) {
                return;
            }
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                Log.w(TAG, "capture target is no longer installed: " + packageName);
                return;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try {
                startActivity(launchIntent);
                Log.i(TAG, "capture ready; opened target " + packageName);
            } catch (RuntimeException error) {
                Log.e(TAG, "failed to open capture target", error);
            }
        }, 250L);
    }

    private void processFrame(ScreenCaptureSource.Frame frame) {
        long inferenceStartMs = SystemClock.elapsedRealtime();
        try {
            if (projectionRecoveryPending.get()) {
                return;
            }
            NcnnDetector currentDetector = detector;
            AutoPlayer currentAutoPlayer = autoPlayer;
            if (currentDetector == null || currentAutoPlayer == null) {
                return;
            }

            updateLogicPlayRuntime(currentAutoPlayer);
            boolean logicPlayEnabled = AppSettings.isLogicPlayModeEnabled(this);
            if (logicPlayEnabled && !previousLogicPlayMode) {
                AppSettings.setAutoSoloModeEnabled(this, true);
                forceLogicPlayWaitLoading(currentAutoPlayer);
            }
            previousLogicPlayMode = logicPlayEnabled;

            AutoContinueController currentAutoContinueController = autoContinueController;
            if (shouldRunAutoContinue()) {
                if (currentAutoContinueController == null) {
                    updateAutoSoloRuntime(true);
                    currentAutoContinueController = autoContinueController;
                }
            } else if (currentAutoContinueController != null || uiButtonDetector != null) {
                updateAutoSoloRuntime(false);
                currentAutoContinueController = null;
            }

            if (currentAutoPlayer.isLogicPlayActive()) {
                if (currentAutoContinueController != null) {
                    currentAutoContinueController.enterLogicPlaying();
                    autoContinueStatus = currentAutoContinueController.statusText();
                }
                setLogicFrameDeliveryPaused(true);
                handleLogicPlayFrame(frame, inferenceStartMs, currentAutoPlayer);
                if (currentAutoPlayer.isLogicPlayFinished()) {
                    finishLogicPlay(currentAutoPlayer, currentAutoContinueController);
                }
                return;
            }

            if (currentAutoContinueController != null) {
                updateClickMode(currentAutoPlayer);
                // A logic timeline determines its own end. Do not run LIVE CLEAR image detection
                // while it is actively playing, otherwise a false match can interrupt the timeline.
                if (!logicPlayEnabled || !currentAutoPlayer.isLogicPlayActive()) {
                    currentAutoContinueController.onFrame(
                            frame.bitmap,
                            frame.displayWidth,
                            frame.displayHeight,
                            isClickBlockedNow(),
                            Collections.emptyList());
                }
                autoContinueStatus = currentAutoContinueController.statusText();
                if (logicPlayEnabled
                        && currentAutoContinueController.consumeEnteredPlayingFromLoading()) {
                    currentAutoContinueController.waitForFirstLogicNote();
                    autoContinueStatus = currentAutoContinueController.statusText();
                    // 状态刚从“等待加载”变成“演奏乐曲”。本帧只提交状态变化，
                    // 不运行音符识别或逻辑时间轴，下一帧才能由真实首音符触发。
                    currentAutoPlayer.setClickEnabled(false);
                    currentAutoPlayer.resetLogicPlayRuntime();
                    currentAutoPlayer.resetNoteRecognitionState();
                    handleAutoContinueFrame(frame, inferenceStartMs, currentAutoContinueController);
                    return;
                }
                if (currentAutoContinueController.shouldSuppressGameRecognition()) {
                    currentAutoPlayer.setClickEnabled(false);
                    currentAutoPlayer.resetLogicPlayRuntime();
                    handleAutoContinueFrame(frame, inferenceStartMs, currentAutoContinueController);
                    return;
                }
            }

            long detectStartMs = SystemClock.elapsedRealtime();
            List<Detection> detections = currentDetector.detect(frame.bitmap);
            long detectMs = Math.max(0L, SystemClock.elapsedRealtime() - detectStartMs);

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

            long previewStartMs = SystemClock.elapsedRealtime();
            updatePreview(frame, detections, inferenceMs, actionYBase);
            long previewMs = Math.max(0L, SystemClock.elapsedRealtime() - previewStartMs);

            long actionStartMs = SystemClock.elapsedRealtime();
            currentAutoPlayer.onFrame(
                    detections,
                    frame.width,
                    frame.height,
                    frame.displayWidth,
                    frame.displayHeight,
                    frame.timestampSec);
            if (logicPlayEnabled
                    && currentAutoContinueController != null
                    && currentAutoPlayer.isLogicPlayActive()) {
                currentAutoContinueController.enterLogicPlaying();
                autoContinueStatus = currentAutoContinueController.statusText();
                setLogicFrameDeliveryPaused(true);
                publishAutoContinueStatusNow();
            }
            long actionMs = Math.max(0L, SystemClock.elapsedRealtime() - actionStartMs);
            long totalMs = Math.max(0L, SystemClock.elapsedRealtime() - inferenceStartMs);

            long now = SystemClock.elapsedRealtime();
            if (now - lastDiagnosticsLogMs >= 1000) {
                lastDiagnosticsLogMs = now;
                Log.i(TAG, "frame=" + frame.width + "x" + frame.height
                        + " display=" + frame.displayWidth + "x" + frame.displayHeight
                        + " fps=" + String.format(Locale.US, "%.1f", currentFps)
                        + " infer=" + inferenceMs + "ms"
                        + " stageMs=capture:" + frame.captureMs
                        + ",detect:" + detectMs
                        + ",status:" + statusMs
                        + ",preview:" + previewMs
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
            frame.close();
            processing.set(false);
        }
    }

    private void handleAutoContinueFrame(
            ScreenCaptureSource.Frame frame,
            long inferenceStartMs,
            AutoContinueController currentAutoContinueController) {
        lastInferenceMs = Math.max(0L, SystemClock.elapsedRealtime() - inferenceStartMs);
        recordProcessedFrame();
        updateRuntimeStatus(0);
        updatePreview(
                frame,
                currentAutoContinueController.buttonDetectionsForPreview(),
                lastInferenceMs,
                AppSettings.getActionY(this),
                true);

        long now = SystemClock.elapsedRealtime();
        if (now - lastDiagnosticsLogMs >= 1000) {
            lastDiagnosticsLogMs = now;
            Log.i(TAG, "frame=" + frame.width + "x" + frame.height
                    + " display=" + frame.displayWidth + "x" + frame.displayHeight
                    + " fps=" + String.format(Locale.US, "%.1f", currentFps)
                    + " infer=" + lastInferenceMs + "ms"
                    + " stageMs=capture:" + frame.captureMs
                    + ",detect:paused:autoContinue"
                    + ",preview:autoContinue"
                    + ",action:paused"
                    + " drop/s=" + String.format(Locale.US, "%.1f", currentDropFps)
                    + " autoContinue=" + autoContinueStatus);
        }
    }


    private void updateLogicPlayRuntime(AutoPlayer currentAutoPlayer) {
        boolean enabled = AppSettings.isLogicPlayModeEnabled(this);
        long now = SystemClock.elapsedRealtime();
        if (lastLogicConfigRefreshMs == Long.MIN_VALUE
                || enabled != cachedLogicPlaySettingEnabled
                || now - lastLogicConfigRefreshMs >= LOGIC_CONFIG_REFRESH_MS) {
            refreshLogicPlayConfig(enabled, now);
        }
        currentAutoPlayer.setLogicPlayConfig(
                cachedLogicPlayEnabled,
                cachedLogicTapIntervalMs,
                cachedLogicTapXRatio,
                cachedLogicEvents);
    }

    private void refreshLogicPlayConfig(boolean enabled, long now) {
        AppSettings.LogicPlayPlan plan = AppSettings.getLogicPlayPlan(this);
        List<AutoPlayer.LogicEvent> events = new ArrayList<>(plan.events.size());
        for (AppSettings.LogicEvent event : plan.events) {
            List<AutoPlayer.LogicPoint> points = new ArrayList<>(event.points.size());
            for (AppSettings.LogicPoint point : event.points) {
                points.add(new AutoPlayer.LogicPoint(point.timeMs, point.xRatio));
            }
            events.add(new AutoPlayer.LogicEvent(
                    event.timeMs,
                    event.type,
                    event.xRatio,
                    event.endXRatio,
                    event.durationMs,
                    points));
        }
        cachedLogicPlaySettingEnabled = enabled;
        cachedLogicPlayEnabled = enabled && plan.valid;
        cachedLogicTapIntervalMs = plan.tapIntervalMs;
        cachedLogicTapXRatio = plan.tapXRatio;
        cachedLogicEvents = events.isEmpty() ? Collections.emptyList() : events;
        lastLogicConfigRefreshMs = now;
    }

    private boolean shouldRunAutoContinue() {
        return AppSettings.isAutoSoloModeEnabled(this)
                || AppSettings.isLogicPlayModeEnabled(this);
    }

    private void forceLogicPlayWaitLoading(AutoPlayer currentAutoPlayer) {
        setLogicFrameDeliveryPaused(false);
        AppSettings.setAutoSoloModeEnabled(this, true);
        updateAutoSoloRuntime(true);
        if (autoContinueController != null) {
            autoContinueController.forceWaitLoading();
            autoContinueStatus = autoContinueController.statusText();
        }
        if (currentAutoPlayer != null) {
            currentAutoPlayer.resetLogicPlayRuntime();
        }
    }

    private void handleLogicPlayFrame(
            ScreenCaptureSource.Frame frame,
            long inferenceStartMs,
            AutoPlayer currentAutoPlayer) {
        // Geometry was captured when logic mode started.  Gesture positions are
        // now advanced by the dedicated 120 Hz clock, not capture-frame cadence.
        currentAutoPlayer.setActionYBase(AppSettings.getActionY(this));
        lastInferenceMs = Math.max(0L, SystemClock.elapsedRealtime() - inferenceStartMs);
        recordProcessedFrame();
        updateRuntimeStatus(0);
        long now = SystemClock.elapsedRealtime();
        if (now - lastDiagnosticsLogMs >= 1000) {
            lastDiagnosticsLogMs = now;
            Log.i(TAG, "frame=" + frame.width + "x" + frame.height
                    + " display=" + frame.displayWidth + "x" + frame.displayHeight
                    + " fps=" + String.format(Locale.US, "%.1f", currentFps)
                    + " infer=" + lastInferenceMs + "ms"
                    + " stageMs=capture:" + frame.captureMs
                    + ",detect:paused:logic"
                    + ",preview:logic"
                    + ",action:logic-clock"
                    + " drop/s=" + String.format(Locale.US, "%.1f", currentDropFps)
                    + " logic=" + currentAutoPlayer.logicPlayStatus());
        }
    }

    private void advanceLogicTimeline() {
        AutoPlayer currentAutoPlayer = autoPlayer;
        if (currentAutoPlayer == null || !currentAutoPlayer.isLogicPlayActive()) {
            return;
        }
        currentAutoPlayer.advanceLogicTimeline(System.nanoTime() / 1_000_000_000.0);
        if (currentAutoPlayer.isLogicPlayFinished()
                && logicTimelineFinishPending.compareAndSet(false, true)) {
            worker.execute(() -> {
                try {
                    if (autoPlayer == currentAutoPlayer && currentAutoPlayer.isLogicPlayFinished()) {
                        finishLogicPlay(currentAutoPlayer, autoContinueController);
                    }
                } finally {
                    logicTimelineFinishPending.set(false);
                }
            });
        }
    }

    private void finishLogicPlay(
            AutoPlayer currentAutoPlayer,
            AutoContinueController currentAutoContinueController) {
        currentAutoPlayer.resetLogicPlayRuntime();
        setLogicFrameDeliveryPaused(false);
        currentAutoPlayer.setClickEnabled(false);
        if (currentAutoContinueController != null) {
            currentAutoContinueController.forceGameEnded();
            autoContinueStatus = currentAutoContinueController.statusText();
        }
        publishAutoContinueStatusNow();
        Log.i(TAG, "logic timeline completed, switching to game ended state");
    }

    private void updateAutoSoloRuntime(boolean enabled) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Toggling modes from the overlay runs on the main thread. UI model construction must
            // follow the same background path as normal capture startup.
            startupWorker.execute(() -> updateAutoSoloRuntime(enabled));
            return;
        }
        if (!enabled) {
            autoContinueController = null;
            if (uiButtonDetector != null) {
                uiButtonDetector.close();
                uiButtonDetector = null;
            }
            autoContinueStatus = AutoContinueController.STATUS_PLAYING;
            return;
        }

        if (injector == null) {
            return;
        }
        if (uiButtonDetector == null) {
            uiButtonDetector = new UiButtonDetector(this);
            Log.i(TAG, "button detector status: " + uiButtonDetector.status());
        }
        if (autoContinueController == null) {
            autoContinueController = new AutoContinueController(injector, uiButtonDetector);
            if (AppSettings.isLogicPlayModeEnabled(this)) {
                autoContinueController.forceWaitLoading();
            } else {
                autoContinueController.reset();
            }
            autoContinueStatus = autoContinueController.statusText();
        }
    }

    private void resetCounters() {
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
        autoContinueStatus = AutoContinueController.STATUS_PLAYING;
        if (autoContinueController != null) {
            autoContinueController.reset();
        }
        totalActions.set(0);
        tapActions.set(0);
        holdActions.set(0);
        flickActions.set(0);
        lastActionText = "none";
    }

    private void recordProcessedFrame() {
        long now = SystemClock.elapsedRealtime();
        synchronized (metricsLock) {
            totalFrames++;
            frameTimesMs.addLast(now);
            refreshFpsWindow(now);
        }
    }

    private void recordDroppedFrame() {
        long now = SystemClock.elapsedRealtime();
        synchronized (metricsLock) {
            totalDroppedFrames++;
            droppedTimesMs.addLast(now);
            refreshFpsWindow(now);
        }
    }

    private void recordAction(String action, int x, int y) {
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
        return AppSettings.isNoClickMode(this)
                || clickResumeAtMs > SystemClock.elapsedRealtime();
    }

    private void updateRuntimeStatus(int detectionCount) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastOverlayUpdateMs >= OVERLAY_UPDATE_INTERVAL_MS) {
            lastOverlayUpdateMs = now;
            updateVisibleStatus(formatStatus(detectionCount), false);
            if (statusOverlay != null) {
                statusOverlay.setNoClickMode(AppSettings.isNoClickMode(this));
                statusOverlay.setClickBlocked(isClickBlockedNow());
                statusOverlay.setAutoSoloMode(AppSettings.isAutoSoloModeEnabled(this));
                statusOverlay.setLogicPlayMode(AppSettings.isLogicPlayModeEnabled(this));
                statusOverlay.setAutoContinueStatus(autoContinueStatus);
                statusOverlay.setScreenRecording(screenRecorder.isRecording());
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

    private void publishAutoContinueStatusNow() {
        updateVisibleStatus(formatStatus(0), false);
        if (statusOverlay != null) {
            statusOverlay.setAutoSoloMode(AppSettings.isAutoSoloModeEnabled(this));
            statusOverlay.setLogicPlayMode(AppSettings.isLogicPlayModeEnabled(this));
            statusOverlay.setAutoContinueStatus(autoContinueStatus);
            statusOverlay.setScreenRecording(screenRecorder.isRecording());
        }
    }

    private void setLogicFrameDeliveryPaused(boolean paused) {
        ScreenCaptureSource source = captureSource;
        if (source != null) {
            source.setFrameDeliveryPaused(paused);
        }
    }

    private String formatStatus(int detectionCount) {
        return String.format(
                Locale.US,
                "运行中\nFPS：%.1f  Drop/s：%.1f  Infer：%dms\nTotal：%d  DropTotal：%d  识别：%d\n状态：%s  自动单人：%s  点击：%s\n动作：%d  Tap：%d  Hold：%d  Flick：%d\n判定：%.0f  映射：%s  最后：%s\n模型：%s",
                currentFps,
                currentDropFps,
                lastInferenceMs,
                totalFrames,
                totalDroppedFrames,
                detectionCount,
                autoContinueStatus,
                AppSettings.isAutoSoloModeEnabled(this) ? "开" : "关",
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
        updatePreview(frame, detections, inferenceMs, actionYBase, false);
    }

    private void updatePreview(
            ScreenCaptureSource.Frame frame,
            List<Detection> detections,
            long inferenceMs,
            double actionYBase,
            boolean buttonLabels) {
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
                actionYBase,
                buttonLabels);
    }

    private void failStart(String text) {
        releaseRuntime();
        running = false;
        updateVisibleStatus(text, true);
    }

    /**
     * A user-choice MediaProjection ends when the chosen game process exits.
     * Stop all input immediately, then bring the activity forward for a fresh
     * single-app authorization. Root grants the AppOp first; Android may still
     * require the user to confirm the system dialog.
     */
    private void recoverStoppedProjection() {
        Log.w(TAG, "single-app media projection stopped; requesting reauthorization");
        processing.set(false);
        running = false;
        AutoPlayer player = autoPlayer;
        if (player != null) {
            player.setClickEnabled(false);
        }
        updateVisibleStatus("录屏已停止，正在申请单应用录屏授权", true);

        updateVisibleStatus("录屏已停止，请在主界面点击开始运行重新授权", true);
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_AUTO_REAUTHORIZE, true);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Log.e(TAG, "failed to launch reauthorization activity", error);
            updateVisibleStatus("录屏已停止，请回软件重新授权单应用录屏", true);
        }
    }

    private void stopEverything() {
        captureTargetLaunchPending.set(false);
        overlaysHidden = false;
        stopHiddenKeyMonitor();
        screenRecorder.stop();
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

    /**
     * MediaSession volume routing is owned by the foreground game, so listen to the root input
     * stream only while the overlay is hidden. This remains independent from logic playback.
     */
    private void startHiddenKeyMonitor() {
        if (!hiddenKeyMonitorRunning.compareAndSet(false, true)) {
            return;
        }
        Thread monitor = new Thread(() -> {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{"su", "-c", "getevent -ql"});
                hiddenKeyMonitorProcess = process;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while (hiddenKeyMonitorRunning.get() && (line = reader.readLine()) != null) {
                        if (!overlaysHidden || !line.contains(" DOWN")) {
                            continue;
                        }
                        if (line.contains("KEY_VOLUMEUP")) {
                            mainHandler.post(this::restoreHiddenOverlays);
                            break;
                        }
                        if (line.contains("KEY_VOLUMEDOWN")) {
                            mainHandler.post(this::stopAndTerminateApp);
                            break;
                        }
                    }
                }
            } catch (IOException error) {
                Log.w(TAG, "hidden overlay key monitor unavailable", error);
            } finally {
                if (hiddenKeyMonitorProcess == process) {
                    hiddenKeyMonitorProcess = null;
                }
                if (process != null) {
                    process.destroy();
                }
                hiddenKeyMonitorRunning.set(false);
            }
        }, "hidden-overlay-key-monitor");
        monitor.start();
    }

    private void stopHiddenKeyMonitor() {
        hiddenKeyMonitorRunning.set(false);
        Process process = hiddenKeyMonitorProcess;
        hiddenKeyMonitorProcess = null;
        if (process != null) {
            process.destroy();
        }
    }

    /**
     * The explicit user stop command should leave no background capture or injector process behind.
     * Only this app package is force-stopped; the selected game process is never targeted.
     */
    private void stopAndTerminateApp() {
        stopEverything();
        stopSelf();
        final String ownPackage = getPackageName();
        Thread terminator = new Thread(() -> {
            Process process = null;
            try {
                process = Runtime.getRuntime().exec(new String[]{
                        "su", "-c", "am force-stop " + ownPackage
                });
                process.waitFor();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                Log.w(TAG, "failed to force-stop own package", exception);
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }, "autoplayer-stop-terminator");
        terminator.start();
    }

    private void releaseRuntime() {
        logicTimelineFinishPending.set(false);
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
        if (uiButtonDetector != null) {
            uiButtonDetector.close();
            uiButtonDetector = null;
        }
        if (detector != null) {
            detector.close();
        }
        detector = null;
        detectorStatus = "";
        resetCounters();
    }

    private void showOverlay(String text) {
        if (overlaysHidden) {
            return;
        }
        if (statusOverlay == null) {
            statusOverlay = new StatusOverlay(this, () -> {
                stopAndTerminateApp();
            }, this::hideOverlays,
                    () -> setPreviewEnabled(!AppSettings.isPreviewEnabled(this)),
                    this::toggleNoClickMode,
                    this::toggleAutoSoloMode,
                    this::toggleLogicPlayMode,
                    this::switchLogicProfile,
                    this::resetPlaybackState,
                    this::toggleScreenRecording,
                    this::runCustomOverlayAction,
                    this::toggleDebugDisplay,
                    this::syncPreviewPosition);
        }
        statusOverlay.show(text);
        statusOverlay.setPreviewEnabled(AppSettings.isPreviewEnabled(this));
        statusOverlay.setNoClickMode(AppSettings.isNoClickMode(this));
        statusOverlay.setClickBlocked(isClickBlockedNow());
        statusOverlay.setAutoSoloMode(AppSettings.isAutoSoloModeEnabled(this));
        statusOverlay.setLogicPlayMode(AppSettings.isLogicPlayModeEnabled(this));
        statusOverlay.setAutoContinueStatus(autoContinueStatus);
        statusOverlay.setScreenRecording(screenRecorder.isRecording());
        statusOverlay.applyLayoutPreferences(
                AppSettings.isOverlayCollapsed(this),
                AppSettings.isOverlayParametersVisible(this));
        refreshOverlayCustomButton();
        statusOverlay.setDebugDisplayEnabled(AppSettings.isDebugDisplayEnabled(this));
    }

    private void hideOverlays() {
        overlaysHidden = true;
        AppSettings.setOverlayHidden(this, true);
        startHiddenKeyMonitor();
        if (previewOverlay != null) {
            previewOverlay.dismiss();
            previewOverlay = null;
        }
        if (statusOverlay != null) {
            statusOverlay.dismiss();
            statusOverlay = null;
        }
    }

    private void restoreHiddenOverlays() {
        if (!running || !overlaysHidden) {
            return;
        }
        overlaysHidden = false;
        AppSettings.setOverlayHidden(this, false);
        stopHiddenKeyMonitor();
        showOverlay(formatStatus(0));
        if (AppSettings.isPreviewEnabled(this)) {
            setPreviewEnabled(true);
        }
    }

    private void toggleScreenRecording() {
        if (!screenRecordingTogglePending.compareAndSet(false, true)) {
            return;
        }
        Thread recordingToggle = new Thread(() -> {
            try {
                String message;
                if (screenRecorder.isRecording()) {
                    String savedPath = screenRecorder.stop();
                    message = savedPath == null
                            ? "录屏未运行"
                            : "录屏已保存：" + savedPath;
                } else {
                    String outputPath = screenRecorder.start();
                    message = "正在录屏：" + outputPath;
                }
                updateNotification(message);
            } catch (Exception error) {
                Log.e(TAG, "failed to toggle root screen recording", error);
                updateNotification("录屏失败：" + error.getMessage());
            } finally {
                screenRecordingTogglePending.set(false);
                if (statusOverlay != null) {
                    statusOverlay.setScreenRecording(screenRecorder.isRecording());
                }
            }
        }, "pjsk-screenrecord-toggle");
        recordingToggle.start();
    }

    private void toggleNoClickMode() {
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

    private void toggleAutoSoloMode() {
        boolean enabled = !AppSettings.isAutoSoloModeEnabled(this);
        AppSettings.setAutoSoloModeEnabled(this, enabled);
        updateAutoSoloRuntime(enabled);
        if (statusOverlay != null) {
            statusOverlay.setAutoSoloMode(enabled);
            statusOverlay.setAutoContinueStatus(autoContinueStatus);
        }
        updateNotification(enabled ? "已开启自动单人模式" : "已关闭自动单人模式");
    }


    private void toggleLogicPlayMode() {
        boolean enabled = !AppSettings.isLogicPlayModeEnabled(this);
        AppSettings.setLogicPlayModeEnabled(this, enabled);
        AutoPlayer currentAutoPlayer = autoPlayer;
        if (enabled) {
            forceLogicPlayWaitLoading(currentAutoPlayer);
            previousLogicPlayMode = true;
        } else {
            previousLogicPlayMode = false;
            setLogicFrameDeliveryPaused(false);
        }
        if (currentAutoPlayer != null) {
            updateLogicPlayRuntime(currentAutoPlayer);
        }
        if (statusOverlay != null) {
            statusOverlay.setAutoSoloMode(AppSettings.isAutoSoloModeEnabled(this));
            statusOverlay.setLogicPlayMode(enabled);
            statusOverlay.setAutoContinueStatus(autoContinueStatus);
        }
        updateNotification(enabled ? "\u5df2\u5f00\u542f\u903b\u8f91\u6f14\u594f\u6a21\u5f0f\uff0c\u7b49\u5f85 LIFE \u52a0\u8f7d" : "\u5df2\u5173\u95ed\u903b\u8f91\u6f14\u594f\u6a21\u5f0f");
    }

    /** Applies the chart selected from the overlay's validated sequence/difficulty controls. */
    private void switchLogicProfile() {
        String label = AppSettings.reloadSelectedLogicChart(this);
        lastLogicConfigRefreshMs = Long.MIN_VALUE;
        AutoPlayer currentAutoPlayer = autoPlayer;
        if (currentAutoPlayer != null) {
            updateLogicPlayRuntime(currentAutoPlayer);
            currentAutoPlayer.resetLogicPlayRuntime();
        }
        setLogicFrameDeliveryPaused(false);
        String message = "\u5df2\u5e94\u7528\u8c31\u9762\uff1a" + label;
        if (statusOverlay != null) {
            statusOverlay.updateStatus(message);
        }
        updateNotification(message);
    }

    private void resetPlaybackState() {
        // A reset must also resume frame delivery. Otherwise resetting while a logic
        // timeline is active leaves the state machine waiting forever without frames.
        setLogicFrameDeliveryPaused(false);
        lastLogicConfigRefreshMs = Long.MIN_VALUE;
        AutoPlayer currentAutoPlayer = autoPlayer;
        if (currentAutoPlayer != null) {
            currentAutoPlayer.resetLogicPlayRuntime();
            currentAutoPlayer.setClickEnabled(false);
        }

        if (shouldRunAutoContinue()) {
            updateAutoSoloRuntime(true);
        }
        if (autoContinueController != null) {
            if (AppSettings.isLogicPlayModeEnabled(this)) {
                autoContinueController.forceWaitLoading();
            } else {
                autoContinueController.reset();
            }
            autoContinueStatus = autoContinueController.statusText();
        } else {
            autoContinueStatus = AutoContinueController.STATUS_PLAYING;
        }

        if (statusOverlay != null) {
            statusOverlay.setAutoContinueStatus(autoContinueStatus);
        }
        publishAutoContinueStatusNow();
        updateNotification("\u5df2\u91cd\u7f6e\u8fd0\u884c\u72b6\u6001\uff1a" + autoContinueStatus);
        Log.i(TAG, "playback state reset to " + autoContinueStatus);
    }

    private void refreshOverlayCustomButton() {
        if (statusOverlay == null) {
            return;
        }
        int action = AppSettings.getOverlayCustomAction(this);
        statusOverlay.setCustomButton(
                AppSettings.overlayCustomActionLabel(action),
                action != AppSettings.OVERLAY_CUSTOM_NONE);
    }

    private void runCustomOverlayAction() {
        switch (AppSettings.getOverlayCustomAction(this)) {
            case AppSettings.OVERLAY_CUSTOM_PREVIEW:
                setPreviewEnabled(!AppSettings.isPreviewEnabled(this));
                return;
            case AppSettings.OVERLAY_CUSTOM_NO_CLICK:
                toggleNoClickMode();
                return;
            case AppSettings.OVERLAY_CUSTOM_AUTO_SOLO:
                toggleAutoSoloMode();
                return;
            case AppSettings.OVERLAY_CUSTOM_LOGIC_PLAY:
                toggleLogicPlayMode();
                return;
            case AppSettings.OVERLAY_CUSTOM_SWITCH_LOGIC:
                switchLogicProfile();
                return;
            case AppSettings.OVERLAY_CUSTOM_RESET_STATE:
                resetPlaybackState();
                return;
            case AppSettings.OVERLAY_CUSTOM_DEBUG_DISPLAY:
                toggleDebugDisplay();
                return;
            case AppSettings.OVERLAY_CUSTOM_SCREEN_RECORD:
                toggleScreenRecording();
                return;
            case AppSettings.OVERLAY_CUSTOM_STOP:
                stopAndTerminateApp();
                return;
            case AppSettings.OVERLAY_CUSTOM_NONE:
            default:
                return;
        }
    }

    private void toggleDebugDisplay() {
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

    private void applyDebugDisplaySetting() {
        final boolean enabled = AppSettings.isDebugDisplayEnabled(this);
        new Thread(() -> DebugDisplayController.setEnabled(enabled),
                "pjsk-apply-debug-display").start();
    }

    private void setPreviewEnabled(boolean enabled) {
        AppSettings.setPreviewEnabled(this, enabled);
        if (statusOverlay != null) {
            statusOverlay.setPreviewEnabled(enabled);
        }

        if (overlaysHidden) {
            return;
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
        syncPreviewPosition();
        previewOverlay.show();
    }

    private void syncPreviewPosition() {
        DetectionPreviewOverlay overlay = previewOverlay;
        StatusOverlay status = statusOverlay;
        if (overlay == null || status == null) {
            return;
        }
        overlay.setAnchorPosition(status.adjacentWindowX(), status.windowY());
    }

    private void updateVisibleStatus(String text, boolean alsoNotification) {
        if (overlaysHidden) {
            if (alsoNotification) {
                updateNotification(text.replace('\n', ' '));
            }
            return;
        }
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
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
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
        logicTimelineWorker.shutdownNow();
        startupWorker.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
