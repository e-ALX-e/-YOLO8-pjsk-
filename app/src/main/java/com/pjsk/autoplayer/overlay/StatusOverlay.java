package com.pjsk.autoplayer.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.pjsk.autoplayer.core.AutoContinueController;
import com.pjsk.autoplayer.settings.AppSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StatusOverlay {
    private static final String TAG = "PJSK-StatusOverlay";
    private static final int MIN_OVERLAY_WIDTH_DP = 210;
    private static final int MAX_OVERLAY_WIDTH_DP = 250;
    private static final int PARAMETER_PANEL_WIDTH_DP = 218;
    private static final int PARAMETER_PANEL_GAP_DP = 8;
    private static final int GRID_BUTTON_HEIGHT_DP = 36;
    private static final int GRID_BUTTON_GAP_DP = 6;
    // Three complete button rows plus a small breathing gap below the last row.
    private static final int CONTROL_AREA_HEIGHT_DP = 138;
    private static final int COLOR_BUTTON = Color.rgb(44, 53, 68);
    private static final int COLOR_BUTTON_BORDER = Color.rgb(84, 99, 122);
    private static final int COLOR_BUTTON_TEXT = Color.rgb(238, 244, 250);

    private final Context context;
    private final Runnable onStopClick;
    private final Runnable onHideClick;
    private final Runnable onPreviewClick;
    private final Runnable onNoClickClick;
    private final Runnable onAutoSoloClick;
    private final Runnable onLogicPlayClick;
    private final Runnable onLogicProfileClick;
    private final Runnable onResetStateClick;
    private final Runnable onScreenRecordClick;
    private final Runnable onCustomClick;
    private final Runnable onDebugDisplayClick;
    private final Runnable onOverlayPositionChanged;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams parameterWindowParams;
    private LinearLayout rootView;
    private LinearLayout contentView;
    private LinearLayout parameterView;
    private TextView statusTitleView;
    private TextView autoContinueStatusView;
    private TextView statusView;
    private TextView collapseButton;
    private TextView hideButton;
    private TextView detailsButton;
    private TextView previewButton;
    private TextView noClickButton;
    private TextView autoSoloButton;
    private TextView logicPlayButton;
    private TextView logicProfileButton;
    private TextView resetStateButton;
    private TextView screenRecordButton;
    private TextView customButton;
    private TextView debugDisplayButton;
    private TextView calibrationStatusView;
    private TextView calibrationLockButton;
    private Spinner logicSongSelector;
    private Spinner logicDifficultySelector;
    private ArrayAdapter<String> logicSongAdapter;
    private ArrayAdapter<String> logicDifficultyAdapter;
    private final List<String> logicSongIds = new ArrayList<>();
    private final List<String> logicDifficulties = new ArrayList<>();
    private boolean updatingLogicChartSelectors;
    private boolean collapsed;
    private boolean parametersVisible;
    private boolean clickBlocked;
    private boolean autoSoloMode;
    private boolean logicPlayMode;
    private String autoContinueStatus = AutoContinueController.STATUS_PLAYING;

    private int startX;
    private int startY;
    private float downRawX;
    private float downRawY;

    public StatusOverlay(
            Context context,
            Runnable onStopClick,
            Runnable onHideClick,
            Runnable onPreviewClick,
            Runnable onNoClickClick,
            Runnable onAutoSoloClick,
            Runnable onLogicPlayClick,
            Runnable onLogicProfileClick,
            Runnable onResetStateClick,
            Runnable onScreenRecordClick,
            Runnable onCustomClick,
            Runnable onDebugDisplayClick,
            Runnable onOverlayPositionChanged) {
        this.context = context.getApplicationContext();
        this.onStopClick = onStopClick;
        this.onHideClick = onHideClick;
        this.onPreviewClick = onPreviewClick;
        this.onNoClickClick = onNoClickClick;
        this.onAutoSoloClick = onAutoSoloClick;
        this.onLogicPlayClick = onLogicPlayClick;
        this.onLogicProfileClick = onLogicProfileClick;
        this.onResetStateClick = onResetStateClick;
        this.onScreenRecordClick = onScreenRecordClick;
        this.onCustomClick = onCustomClick;
        this.onDebugDisplayClick = onDebugDisplayClick;
        this.onOverlayPositionChanged = onOverlayPositionChanged;
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    public void show(String statusText) {
        mainHandler.post(() -> showOnMain(statusText));
    }

    public boolean isShown() {
        return rootView != null;
    }

    /** Returns the first free x coordinate directly to the right of this overlay group. */
    public int adjacentWindowX() {
        int baseWidth = collapsed && rootView != null && rootView.getWidth() > 0
                ? rootView.getWidth()
                : overlayWidthPx();
        int x = params == null ? 0 : params.x;
        if (parametersVisible && !collapsed) {
            baseWidth += dp(PARAMETER_PANEL_GAP_DP + PARAMETER_PANEL_WIDTH_DP);
        }
        return x + baseWidth + dp(PARAMETER_PANEL_GAP_DP);
    }

    public int windowY() {
        return params == null ? 0 : params.y;
    }

    public void updateStatus(String statusText) {
        mainHandler.post(() -> {
            if (statusView != null) {
                statusView.setText(statusText);
            }
        });
    }

    public void setPreviewEnabled(boolean enabled) {
        mainHandler.post(() -> {
            if (previewButton != null) {
                previewButton.setText(enabled ? "关闭预览" : "开启预览");
            }
        });
    }

    public void setNoClickMode(boolean enabled) {
        mainHandler.post(() -> {
            if (noClickButton != null) {
                noClickButton.setText(enabled ? "允许点击" : "不点击");
            }
        });
    }

    public void setAutoSoloMode(boolean enabled) {
        mainHandler.post(() -> {
            autoSoloMode = enabled;
            if (autoSoloButton != null) {
                autoSoloButton.setText(enabled ? "单人开" : "单人关");
                autoSoloButton.setTextColor(enabled
                        ? Color.rgb(94, 232, 142)
                        : Color.rgb(255, 194, 87));
            }
        });
    }


    public void setLogicPlayMode(boolean enabled) {
        mainHandler.post(() -> {
            logicPlayMode = enabled;
            if (logicPlayButton != null) {
                logicPlayButton.setText(enabled ? "\u903b\u8f91\u5f00" : "\u903b\u8f91\u5173");
                logicPlayButton.setTextColor(enabled
                        ? Color.rgb(126, 214, 255)
                        : Color.rgb(255, 194, 87));
            }
        });
    }

    public void setClickBlocked(boolean blocked) {
        mainHandler.post(() -> {
            clickBlocked = blocked;
            updateClickModeColor();
        });
    }

    public void setGameEndedPaused(boolean paused) {
        setAutoContinueStatus(paused
                ? AutoContinueController.STATUS_GAME_ENDED
                : AutoContinueController.STATUS_PLAYING);
    }

    public void setAutoContinueStatus(String status) {
        mainHandler.post(() -> {
            autoContinueStatus = status == null
                    ? AutoContinueController.STATUS_PLAYING
                    : status;
            if (autoContinueStatusView != null) {
                autoContinueStatusView.setText(autoContinueStatus);
                updateAutoContinueStatusColor();
            }
            updateClickModeColor();
        });
    }

    public void setDebugDisplayEnabled(boolean enabled) {
        mainHandler.post(() -> {
            if (debugDisplayButton != null) {
                debugDisplayButton.setText(enabled ? "关调试" : "调试显示");
            }
        });
    }

    public void setScreenRecording(boolean recording) {
        mainHandler.post(() -> {
            if (screenRecordButton != null) {
                screenRecordButton.setText(recording ? "结束录屏" : "录屏");
                screenRecordButton.setTextColor(recording
                        ? Color.rgb(255, 126, 126)
                        : Color.rgb(225, 232, 240));
            }
        });
    }

    public void setCustomButton(String actionLabel, boolean visible) {
        mainHandler.post(() -> {
            if (customButton == null) {
                return;
            }
            customButton.setText("\u81ea\u5b9a\u4e49\uff1a" + actionLabel);
            customButton.setVisibility(visible ? View.VISIBLE : View.GONE);
            updateLayout();
        });
    }

    /** Lets the main screen expose the same parameter toggle as the overlay. */
    public void toggleParameters() {
        mainHandler.post(() -> setParametersVisible(!parametersVisible));
    }

    /** Lets the main screen expose the same collapse action as the overlay header. */
    public void toggleCollapsed() {
        mainHandler.post(() -> setCollapsed(!collapsed));
    }

    /** Applies layout choices saved before the capture service was started. */
    public void applyLayoutPreferences(boolean collapsed, boolean parametersVisible) {
        mainHandler.post(() -> {
            setCollapsed(collapsed);
            setParametersVisible(parametersVisible);
        });
    }

    public void dismiss() {
        mainHandler.post(() -> {
            if (windowManager != null && rootView != null) {
                try {
                    windowManager.removeView(rootView);
                } catch (IllegalArgumentException ignored) {
                }
            }
            dismissParameterWindow();
            clearViews();
        });
    }

    private void showOnMain(String statusText) {
        if (!canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission is not granted");
            return;
        }
        if (rootView != null) {
            updateStatus(statusText);
            return;
        }

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "WindowManager is null");
            return;
        }

        rootView = buildView(statusText);
        params = new WindowManager.LayoutParams(
                overlayWidthPx(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.START | Gravity.TOP;
        params.x = 0;
        params.y = 0;

        try {
            windowManager.addView(rootView, params);
            if (parametersVisible && !collapsed) {
                showParameterWindow();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "failed to show overlay", e);
            clearViews();
        }
    }

    private LinearLayout buildView(String statusText) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.setBackground(makeBackground());

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        // Keep dragging on the header so the control list can receive scroll gestures.
        header.setOnTouchListener((view, event) -> handleDrag(event));

        statusTitleView = new TextView(context);
        statusTitleView.setText("状态");
        statusTitleView.setTextSize(14f);
        statusTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        updateClickModeColor();
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, 0, dp(8), 0);
        titleParams.weight = 1f;
        header.addView(statusTitleView, titleParams);

        autoContinueStatusView = new TextView(context);
        autoContinueStatusView.setText(autoContinueStatus);
        autoContinueStatusView.setTextSize(10f);
        autoContinueStatusView.setTypeface(Typeface.DEFAULT_BOLD);
        autoContinueStatusView.setGravity(Gravity.CENTER);
        autoContinueStatusView.setPadding(dp(5), 0, dp(5), 0);
        autoContinueStatusView.setSingleLine(true);
        updateAutoContinueStatusColor();
        LinearLayout.LayoutParams autoStatusParams = new LinearLayout.LayoutParams(dp(70), dp(28));
        autoStatusParams.setMargins(0, 0, dp(6), 0);
        header.addView(autoContinueStatusView, autoStatusParams);

        collapseButton = makeSmallButton("折叠");
        collapseButton.setOnClickListener(v -> setCollapsed(!collapsed));
        header.addView(collapseButton, new LinearLayout.LayoutParams(dp(48), dp(32)));

        hideButton = makeSmallButton("隐藏");
        hideButton.setContentDescription("隐藏窗口");
        hideButton.setOnClickListener(v -> onHideClick.run());
        LinearLayout.LayoutParams hideParams = new LinearLayout.LayoutParams(dp(40), dp(32));
        hideParams.setMargins(dp(4), 0, 0, 0);
        header.addView(hideButton, hideParams);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        contentView = new LinearLayout(context);
        contentView.setOrientation(LinearLayout.VERTICAL);

        parameterView = new LinearLayout(context);
        parameterView.setOrientation(LinearLayout.VERTICAL);
        parameterView.setPadding(dp(8), dp(6), dp(8), dp(6));
        parameterView.setBackground(makeButtonBackground(
                Color.rgb(31, 40, 55),
                Color.rgb(104, 133, 168)));
        parameterView.setOnTouchListener((view, event) -> handleDrag(event));

        TextView parameterTitle = new TextView(context);
        parameterTitle.setText("\u8bc6\u522b\u53c2\u6570");
        parameterTitle.setTextColor(Color.rgb(151, 210, 255));
        parameterTitle.setTextSize(12f);
        parameterTitle.setTypeface(Typeface.DEFAULT_BOLD);
        parameterView.addView(parameterTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(context);
        statusView.setText(statusText);
        statusView.setTextColor(Color.rgb(225, 232, 240));
        statusView.setTextSize(11f);
        statusView.setSingleLine(false);
        statusView.setHorizontallyScrolling(false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(4), 0, dp(8));
        parameterView.addView(statusView, statusParams);

        ScrollView buttonScrollView = new ScrollView(context);
        buttonScrollView.setFillViewport(false);
        buttonScrollView.setVerticalScrollBarEnabled(true);
        buttonScrollView.setClipToPadding(true);
        buttonScrollView.setClipChildren(true);
        LinearLayout buttonList = new LinearLayout(context);
        buttonList.setOrientation(LinearLayout.VERTICAL);
        buttonScrollView.addView(buttonList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout firstRow = makeButtonRow();

        previewButton = makeSmallButton("开启预览");
        previewButton.setOnClickListener(v -> onPreviewClick.run());
        firstRow.addView(previewButton, makeGridButtonParams(true));

        noClickButton = makeSmallButton("不点击");
        noClickButton.setOnClickListener(v -> onNoClickClick.run());
        firstRow.addView(noClickButton, makeGridButtonParams(true));

        autoSoloButton = makeSmallButton("单人开");
        autoSoloButton.setOnClickListener(v -> onAutoSoloClick.run());
        firstRow.addView(autoSoloButton, makeGridButtonParams(false));
        setAutoSoloMode(autoSoloMode);

        buttonList.addView(firstRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout secondRow = makeButtonRow();

        detailsButton = makeSmallButton("显示参数");
        detailsButton.setOnClickListener(v -> setParametersVisible(!parametersVisible));
        secondRow.addView(detailsButton, makeGridButtonParams(true));

        logicPlayButton = makeSmallButton("\u903b\u8f91\u5173");
        logicPlayButton.setOnClickListener(v -> onLogicPlayClick.run());
        secondRow.addView(logicPlayButton, makeGridButtonParams(true));
        setLogicPlayMode(logicPlayMode);

        debugDisplayButton = makeSmallButton("\u8c03\u8bd5\u663e\u793a");
        debugDisplayButton.setOnClickListener(v -> onDebugDisplayClick.run());
        secondRow.addView(debugDisplayButton, makeGridButtonParams(false));

        buttonList.addView(secondRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout thirdRow = makeButtonRow();
        resetStateButton = makeSmallButton("\u91cd\u7f6e\u72b6\u6001");
        resetStateButton.setOnClickListener(v -> onResetStateClick.run());
        thirdRow.addView(resetStateButton, makeGridButtonParams(true));

        logicProfileButton = makeSmallButton("\u5e94\u7528\u8c31\u9762");
        logicProfileButton.setOnClickListener(v -> onLogicProfileClick.run());
        thirdRow.addView(logicProfileButton, makeGridButtonParams(true));

        TextView stop = makeSmallButton("\u505c\u6b62");
        stop.setOnClickListener(v -> onStopClick.run());
        thirdRow.addView(stop, makeGridButtonParams(false));

        buttonList.addView(thirdRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView chartLabel = new TextView(context);
        chartLabel.setText("\u5e8f\u53f7 / \u96be\u5ea6");
        chartLabel.setTextColor(Color.rgb(188, 203, 222));
        chartLabel.setTextSize(10f);
        LinearLayout.LayoutParams chartLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        chartLabelParams.setMargins(0, dp(7), 0, dp(1));
        buttonList.addView(chartLabel, chartLabelParams);

        LinearLayout chartRow = makeButtonRow();
        logicSongSelector = new Spinner(context);
        logicSongAdapter = makeLogicSelectorAdapter();
        logicSongSelector.setAdapter(logicSongAdapter);
        styleLogicSelector(logicSongSelector);
        logicSongSelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                if (updatingLogicChartSelectors || position < 0 || position >= logicSongIds.size()) {
                    return;
                }
                refreshLogicDifficultySelector(logicSongIds.get(position), true);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        chartRow.addView(logicSongSelector, makeChartSelectorParams(true));

        logicDifficultySelector = new Spinner(context);
        logicDifficultyAdapter = makeLogicSelectorAdapter();
        logicDifficultySelector.setAdapter(logicDifficultyAdapter);
        styleLogicSelector(logicDifficultySelector);
        logicDifficultySelector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                if (updatingLogicChartSelectors || position < 0 || position >= logicDifficulties.size()) {
                    return;
                }
                String songId = selectedLogicSongId();
                String difficulty = logicDifficulties.get(position);
                if (AppSettings.selectLogicChart(context, songId, difficulty)) {
                    updateSelectedChartButton();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        chartRow.addView(logicDifficultySelector, makeChartSelectorParams(false));
        buttonList.addView(chartRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout fourthRow = makeButtonRow();
        screenRecordButton = makeSmallButton("录屏");
        screenRecordButton.setOnClickListener(v -> onScreenRecordClick.run());
        fourthRow.addView(screenRecordButton, makeWideButtonParams());
        buttonList.addView(fourthRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout fifthRow = makeButtonRow();
        customButton = makeSmallButton("\u81ea\u5b9a\u4e49");
        customButton.setVisibility(View.GONE);
        customButton.setOnClickListener(v -> onCustomClick.run());
        fifthRow.addView(customButton, makeWideButtonParams());
        buttonList.addView(fifthRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout calibrationInfoRow = makeButtonRow();
        calibrationStatusView = new TextView(context);
        calibrationStatusView.setTextColor(Color.rgb(225, 232, 240));
        calibrationStatusView.setTextSize(10.5f);
        calibrationStatusView.setGravity(Gravity.CENTER_VERTICAL);
        calibrationInfoRow.addView(calibrationStatusView, new LinearLayout.LayoutParams(
                0, dp(30), 1f));

        calibrationLockButton = makeSmallButton("");
        calibrationLockButton.setOnClickListener(v -> {
            AppSettings.setActionYCalibrationLocked(
                    context,
                    !AppSettings.isActionYCalibrationLocked(context));
            refreshActionYCalibrationControls();
        });
        LinearLayout.LayoutParams calibrationLockParams = new LinearLayout.LayoutParams(dp(94), dp(30));
        calibrationLockParams.setMargins(dp(6), 0, 0, 0);
        calibrationInfoRow.addView(calibrationLockButton, calibrationLockParams);
        LinearLayout.LayoutParams calibrationInfoParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        calibrationInfoParams.setMargins(0, dp(6), 0, 0);
        buttonList.addView(calibrationInfoRow, calibrationInfoParams);

        LinearLayout calibrationActionRow = makeButtonRow();
        TextView calibrationUp = makeSmallButton("\u4e0a\u79fb");
        calibrationUp.setOnClickListener(v -> adjustActionY(-1.0));
        calibrationActionRow.addView(calibrationUp, makeGridButtonParams(true));

        TextView calibrationReset = makeSmallButton("\u91cd\u7f6e");
        calibrationReset.setOnClickListener(v -> {
            if (AppSettings.isActionYCalibrationLocked(context)) {
                return;
            }
            AppSettings.resetActionY(context);
            refreshActionYCalibrationControls();
        });
        calibrationActionRow.addView(calibrationReset, makeGridButtonParams(true));

        TextView calibrationDown = makeSmallButton("\u4e0b\u79fb");
        calibrationDown.setOnClickListener(v -> adjustActionY(1.0));
        calibrationActionRow.addView(calibrationDown, makeGridButtonParams(false));
        buttonList.addView(calibrationActionRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        refreshActionYCalibrationControls();

        contentView.addView(buttonScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(CONTROL_AREA_HEIGHT_DP)));
        root.addView(contentView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        refreshLogicChartSelectors();
        return root;
    }

    private void refreshLogicChartSelectors() {
        if (logicSongAdapter == null || logicDifficultyAdapter == null) {
            return;
        }
        updatingLogicChartSelectors = true;
        logicSongIds.clear();
        logicSongIds.addAll(AppSettings.logicChartSongIds(context));
        logicSongAdapter.clear();
        for (String songId : logicSongIds) {
            logicSongAdapter.add(songId);
        }
        logicSongAdapter.notifyDataSetChanged();

        if (logicSongIds.isEmpty()) {
            logicDifficulties.clear();
            logicDifficultyAdapter.clear();
            logicDifficultyAdapter.notifyDataSetChanged();
            updatingLogicChartSelectors = false;
            updateSelectedChartButton();
            return;
        }
        String selectedSong = AppSettings.selectedLogicChartSongId(context);
        int songIndex = logicSongIds.indexOf(selectedSong);
        if (songIndex < 0) {
            songIndex = 0;
        }
        logicSongSelector.setSelection(songIndex, false);
        refreshLogicDifficultySelector(logicSongIds.get(songIndex), false);
        updatingLogicChartSelectors = false;
        updateSelectedChartButton();
    }

    private void refreshLogicDifficultySelector(String songId, boolean selectDefault) {
        if (logicDifficultyAdapter == null) {
            return;
        }
        boolean wasUpdating = updatingLogicChartSelectors;
        updatingLogicChartSelectors = true;
        logicDifficulties.clear();
        logicDifficulties.addAll(AppSettings.logicChartDifficulties(context, songId));
        logicDifficultyAdapter.clear();
        for (String difficulty : logicDifficulties) {
            logicDifficultyAdapter.add(difficulty.toUpperCase(Locale.ROOT));
        }
        logicDifficultyAdapter.notifyDataSetChanged();
        int difficultyIndex = logicDifficulties.indexOf(AppSettings.selectedLogicChartDifficulty(context));
        if (difficultyIndex < 0) {
            difficultyIndex = 0;
        }
        if (!logicDifficulties.isEmpty()) {
            logicDifficultySelector.setSelection(difficultyIndex, false);
            if (selectDefault) {
                AppSettings.selectLogicChart(context, songId, logicDifficulties.get(difficultyIndex));
            }
        }
        updatingLogicChartSelectors = wasUpdating;
        updateSelectedChartButton();
    }

    private String selectedLogicSongId() {
        int position = logicSongSelector == null ? -1 : logicSongSelector.getSelectedItemPosition();
        return position >= 0 && position < logicSongIds.size()
                ? logicSongIds.get(position)
                : AppSettings.selectedLogicChartSongId(context);
    }

    private void updateSelectedChartButton() {
        if (logicProfileButton == null) {
            return;
        }
        String songId = AppSettings.selectedLogicChartSongId(context);
        String difficulty = AppSettings.selectedLogicChartDifficulty(context);
        logicProfileButton.setText(songId.isEmpty() || difficulty.isEmpty()
                ? "\u5e94\u7528\u8c31\u9762"
                : "\u5e94\u7528 " + songId + " " + difficulty.toUpperCase(Locale.ROOT));
    }

    private LinearLayout.LayoutParams makeChartSelectorParams(boolean hasRightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(36), 1f);
        params.setMargins(0, 0, hasRightMargin ? dp(5) : 0, 0);
        return params;
    }

    private ArrayAdapter<String> makeLogicSelectorAdapter() {
        return new ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, new ArrayList<>()) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return makeSelectorText(getItem(position), false);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return makeSelectorText(getItem(position), true);
            }
        };
    }

    private TextView makeSelectorText(String text, boolean dropdownItem) {
        TextView view = new TextView(context);
        view.setText(text == null ? "" : text);
        view.setTextColor(COLOR_BUTTON_TEXT);
        view.setTextSize(12f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setSingleLine(true);
        view.setPadding(dp(10), 0, dp(8), 0);
        if (dropdownItem) {
            view.setMinHeight(dp(42));
            view.setBackgroundColor(Color.rgb(35, 45, 62));
        }
        return view;
    }

    private void styleLogicSelector(Spinner selector) {
        selector.setPadding(dp(1), 0, dp(1), 0);
        selector.setBackground(makeButtonBackground(
                Color.rgb(24, 33, 48),
                Color.rgb(123, 163, 207)));
    }

    /**
     * Overlay buttons deliberately use a TextView instead of the platform Button widget.
     * Some landscape overlay windows draw Button's built-in inset/foreground incorrectly;
     * this keeps every button's background and touch area inside the same explicit bounds.
     */
    private TextView makeSmallButton(String text) {
        TextView button = new OverlayActionButton(context);
        button.setText(text);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setTextSize(10.5f);
        button.setTextColor(COLOR_BUTTON_TEXT);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private LinearLayout makeButtonRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams makeGridButtonParams(boolean hasRightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(GRID_BUTTON_HEIGHT_DP), 1f);
        params.setMargins(0, dp(GRID_BUTTON_GAP_DP), hasRightMargin ? dp(5) : 0, dp(2));
        return params;
    }

    private LinearLayout.LayoutParams makeWideButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(GRID_BUTTON_HEIGHT_DP));
        params.setMargins(0, dp(GRID_BUTTON_GAP_DP), 0, dp(2));
        return params;
    }

    /** Draws the full rounded outline inside its own bounds, including the lower corners. */
    private final class OverlayActionButton extends TextView {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        OverlayActionButton(Context context) {
            super(context);
            fillPaint.setColor(COLOR_BUTTON);
            strokePaint.setColor(COLOR_BUTTON_BORDER);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(1));
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float inset = strokePaint.getStrokeWidth() / 2f;
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float radius = dp(7);
            canvas.drawRoundRect(bounds, radius, radius, fillPaint);
            canvas.drawRoundRect(bounds, radius, radius, strokePaint);
            super.onDraw(canvas);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    private void setParametersVisible(boolean visible) {
        parametersVisible = visible;
        AppSettings.setOverlayParametersVisible(context, visible);
        if (visible && !collapsed) {
            showParameterWindow();
        } else {
            dismissParameterWindow();
        }
        if (detailsButton != null) {
            detailsButton.setText(visible ? "隐藏参数" : "显示参数");
        }
        updateLayout();
        notifyPositionChanged();
    }

    private void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        AppSettings.setOverlayCollapsed(context, collapsed);
        if (contentView != null) {
            contentView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (!collapsed && parametersVisible) {
            showParameterWindow();
        } else {
            dismissParameterWindow();
        }
        if (collapseButton != null) {
            collapseButton.setText(collapsed ? "展开" : "折叠");
        }
        if (rootView != null) {
            rootView.setPadding(
                    dp(10),
                    collapsed ? dp(6) : dp(8),
                    dp(10),
                    collapsed ? dp(6) : dp(8));
        }
        updateLayout();
        notifyPositionChanged();
    }

    private void updateClickModeColor() {
        if (statusTitleView != null) {
            int color;
            if (AutoContinueController.STATUS_GAME_ENDED.equals(autoContinueStatus)) {
                color = Color.rgb(255, 194, 87);
            } else if (AutoContinueController.STATUS_SELECT_SONG.equals(autoContinueStatus)) {
                color = Color.rgb(126, 214, 255);
            } else if (AutoContinueController.STATUS_WAIT_LOADING.equals(autoContinueStatus)) {
                color = Color.rgb(215, 169, 255);
            } else if (clickBlocked) {
                color = Color.rgb(255, 102, 102);
            } else {
                color = Color.rgb(94, 232, 142);
            }
            statusTitleView.setTextColor(color);
        }
    }

    private void updateAutoContinueStatusColor() {
        if (autoContinueStatusView == null) {
            return;
        }
        int textColor;
        int backgroundColor;
        if (AutoContinueController.STATUS_GAME_ENDED.equals(autoContinueStatus)) {
            textColor = Color.rgb(255, 226, 159);
            backgroundColor = Color.argb(80, 255, 194, 87);
        } else if (AutoContinueController.STATUS_SELECT_SONG.equals(autoContinueStatus)) {
            textColor = Color.rgb(126, 214, 255);
            backgroundColor = Color.argb(80, 76, 169, 255);
        } else if (AutoContinueController.STATUS_WAIT_LOADING.equals(autoContinueStatus)) {
            textColor = Color.rgb(215, 169, 255);
            backgroundColor = Color.argb(80, 164, 96, 210);
        } else {
            textColor = Color.rgb(94, 232, 142);
            backgroundColor = Color.argb(80, 64, 200, 118);
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(backgroundColor);
        background.setCornerRadius(dp(7));
        background.setStroke(
                dp(1),
                Color.argb(
                        120,
                        (textColor >> 16) & 0xff,
                        (textColor >> 8) & 0xff,
                        textColor & 0xff));
        autoContinueStatusView.setTextColor(textColor);
        autoContinueStatusView.setBackground(background);
    }

    private void updateLayout() {
        if (windowManager != null && rootView != null && params != null) {
            try {
                params.width = collapsed ? WindowManager.LayoutParams.WRAP_CONTENT : overlayWidthPx();
                windowManager.updateViewLayout(rootView, params);
            } catch (IllegalArgumentException ignored) {
            }
        }
        updateParameterWindowPosition();
    }

    private void showParameterWindow() {
        if (windowManager == null || rootView == null || parameterView == null
                || parameterWindowParams != null || collapsed || !parametersVisible) {
            return;
        }
        WindowManager.LayoutParams sideParams = new WindowManager.LayoutParams(
                dp(PARAMETER_PANEL_WIDTH_DP),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        sideParams.gravity = Gravity.START | Gravity.TOP;
        sideParams.x = params.x + overlayWidthPx() + dp(PARAMETER_PANEL_GAP_DP);
        sideParams.y = params.y;
        try {
            windowManager.addView(parameterView, sideParams);
            parameterWindowParams = sideParams;
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to show parameter window", e);
        }
    }

    private void dismissParameterWindow() {
        if (windowManager == null || parameterView == null || parameterWindowParams == null) {
            parameterWindowParams = null;
            return;
        }
        try {
            windowManager.removeView(parameterView);
        } catch (IllegalArgumentException ignored) {
        }
        parameterWindowParams = null;
    }

    private void updateParameterWindowPosition() {
        if (windowManager == null || parameterView == null || parameterWindowParams == null || params == null) {
            return;
        }
        try {
            parameterWindowParams.x = params.x + overlayWidthPx() + dp(PARAMETER_PANEL_GAP_DP);
            parameterWindowParams.y = params.y;
            windowManager.updateViewLayout(parameterView, parameterWindowParams);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private boolean handleDrag(MotionEvent event) {
        if (params == null || windowManager == null) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = params.x;
                startY = params.y;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                return false;

            case MotionEvent.ACTION_MOVE:
                params.x = startX + Math.round(event.getRawX() - downRawX);
                params.y = startY + Math.round(event.getRawY() - downRawY);
                updateLayout();
                notifyPositionChanged();
                return true;

            default:
                return false;
        }
    }

    private void adjustActionY(double delta) {
        if (AppSettings.isActionYCalibrationLocked(context)) {
            return;
        }
        AppSettings.setActionY(context, AppSettings.getActionY(context) + delta);
        refreshActionYCalibrationControls();
    }

    private void refreshActionYCalibrationControls() {
        if (calibrationStatusView != null) {
            calibrationStatusView.setText(String.format(
                    Locale.US,
                    "\u5224\u5b9a\u70b9 %.0f",
                    AppSettings.getActionY(context)));
        }
        if (calibrationLockButton != null) {
            boolean locked = AppSettings.isActionYCalibrationLocked(context);
            calibrationLockButton.setText(locked
                    ? "\u89e3\u9501\u5224\u5b9a\u7ebf"
                    : "\u9501\u5b9a\u5224\u5b9a\u7ebf");
            calibrationLockButton.setTextColor(locked
                    ? Color.rgb(255, 194, 87)
                    : Color.rgb(94, 232, 142));
        }
    }

    private void clearViews() {
        rootView = null;
        contentView = null;
        parameterView = null;
        statusTitleView = null;
        autoContinueStatusView = null;
        statusView = null;
        collapseButton = null;
        hideButton = null;
        detailsButton = null;
        previewButton = null;
        noClickButton = null;
        autoSoloButton = null;
        logicPlayButton = null;
        logicProfileButton = null;
        resetStateButton = null;
        screenRecordButton = null;
        customButton = null;
        debugDisplayButton = null;
        calibrationStatusView = null;
        calibrationLockButton = null;
        logicSongSelector = null;
        logicDifficultySelector = null;
        logicSongAdapter = null;
        logicDifficultyAdapter = null;
        logicSongIds.clear();
        logicDifficulties.clear();
        updatingLogicChartSelectors = false;
        params = null;
        parameterWindowParams = null;
        parametersVisible = false;
        collapsed = false;
        autoContinueStatus = AutoContinueController.STATUS_PLAYING;
    }

    private void notifyPositionChanged() {
        if (onOverlayPositionChanged != null) {
            onOverlayPositionChanged.run();
        }
    }

    private GradientDrawable makeBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(238, 22, 28, 39));
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), Color.argb(145, 164, 183, 211));
        return drawable;
    }

    private GradientDrawable makeButtonBackground(int color, int borderColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(7));
        drawable.setStroke(dp(1), borderColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private int overlayWidthPx() {
        float density = context.getResources().getDisplayMetrics().density;
        int screenWidthDp = Math.round(context.getResources().getDisplayMetrics().widthPixels / density);
        int targetDp = Math.round(screenWidthDp * 0.58f);
        targetDp = Math.max(MIN_OVERLAY_WIDTH_DP, Math.min(MAX_OVERLAY_WIDTH_DP, targetDp));
        return dp(targetDp);
    }
}
