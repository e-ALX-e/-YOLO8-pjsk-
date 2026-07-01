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
import com.pjsk.autoplayer.ncnn.UiButtonDetector;
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
    private static final long CLICK_RESUME_DELAY_MS = 5000;

    private static volatile boolean running;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final Object metricsLock = new Object();

    private ScreenCaptureSource captureSource;
    private NcnnDetector detector;
    private UiButtonDetector uiButtonDetector;
    private AutoPlayer autoPlayer;
    private AutoContinueController autoContinueController;
    private RootEventInjector injector;
    private StatusOverlay statusOverlay;
    private DetectionPreviewOverlay previewOverlay;

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
    private String autoContinueStatus = AutoContinueController.STATUS_PLAYING;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_SET_PREVIEW.equals(action)) {
            setPreviewEnabled(intent.getBooleanExtra(
                    EXTRA_PREVIEW_ENABLED,
                    AppSettings.isPreviewEnabled(this)));
            return running ? START_STICKY : START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startCapture(resultCode, resultData);
            return START_STICKY;
        }

        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent resultData) {
        if (resultData == null) {
            Log.e(TAG, "missing MediaProjection result data");
            updateNotification("启动失败：缺少录屏授权");
            return;
        }

        stopEverything();
        running = true;

        detector = new NcnnDetector(this);
        detectorStatus = detector.status();
        Log.i(TAG, "detector status: " + detectorStatus);
        injector = new RootEventInjector(this);
        uiButtonDetector = new UiButtonDetector(this);
        Log.i(TAG, "button detector status: " + uiButtonDetector.status());
        autoPlayer = new AutoPlayer(injector, this::recordAction);
        autoContinueController = new AutoContinueController(injector, uiButtonDetector);
        resetCounters();
        previousNoClickMode = AppSettings.isNoClickMode(this);
        clickResumeAtMs = 0L;
        showOverlay("启动中\n模型：" + detectorStatus);
        setPreviewEnabled(AppSettings.isPreviewEnabled(this));

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

            AutoContinueController currentAutoContinueController = autoContinueController;
            if (currentAutoContinueController != null) {
                updateClickMode(currentAutoPlayer);
                currentAutoContinueController.onFrame(
                        frame.bitmap,
                        frame.displayWidth,
                        frame.displayHeight,
                        isClickBlockedNow());
                autoContinueStatus = currentAutoContinueController.statusText();
            }

            if (currentAutoContinueController != null
                    && currentAutoContinueController.shouldSuppressGameRecognition()) {
                currentAutoPlayer.setClickEnabled(false);
                handleAutoContinueFrame(frame, inferenceStartMs);
                return;
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

    private void handleAutoContinueFrame(ScreenCaptureSource.Frame frame, long inferenceStartMs) {
        lastInferenceMs = Math.max(0L, SystemClock.elapsedRealtime() - inferenceStartMs);
        recordProcessedFrame();
        updateRuntimeStatus(0);
        updatePreview(frame, Collections.emptyList(), lastInferenceMs, AppSettings.getActionY(this));

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
                statusOverlay.setAutoContinueStatus(autoContinueStatus);
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
        return String.format(
                Locale.US,
                "运行中\nFPS：%.1f  Drop/s：%.1f  Infer：%dms\nTotal：%d  DropTotal：%d  识别：%d\n状态：%s  点击：%s  动作：%d  Tap：%d  Hold：%d  Flick：%d\n判定：%.0f  映射：%s  最后：%s\n模型：%s",
                currentFps,
                currentDropFps,
                lastInferenceMs,
                totalFrames,
                totalDroppedFrames,
                detectionCount,
                autoContinueStatus,
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
        releaseRuntime();
        running = false;
        updateVisibleStatus(text, true);
    }

    private void stopEverything() {
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
        statusOverlay.setAutoContinueStatus(autoContinueStatus);
        statusOverlay.setDebugDisplayEnabled(AppSettings.isDebugDisplayEnabled(this));
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

    private void setPreviewEnabled(boolean enabled) {
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
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

