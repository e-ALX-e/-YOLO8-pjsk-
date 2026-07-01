package com.pjsk.autoplayer.overlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class StatusOverlay {
    private static final String TAG = "PJSK-StatusOverlay";

    private final Context context;
    private final Runnable onStopClick;
    private final Runnable onPreviewClick;
    private final Runnable onNoClickClick;
    private final Runnable onDebugDisplayClick;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout rootView;
    private LinearLayout contentView;
    private LinearLayout parameterView;
    private TextView statusTitleView;
    private TextView statusView;
    private Button collapseButton;
    private Button detailsButton;
    private Button previewButton;
    private Button noClickButton;
    private Button debugDisplayButton;
    private boolean collapsed;
    private boolean parametersVisible;
    private boolean gameEndedPaused;

    private int startX;
    private int startY;
    private float downRawX;
    private float downRawY;

    public StatusOverlay(
            Context context,
            Runnable onStopClick,
            Runnable onPreviewClick,
            Runnable onNoClickClick,
            Runnable onDebugDisplayClick) {
        this.context = context.getApplicationContext();
        this.onStopClick = onStopClick;
        this.onPreviewClick = onPreviewClick;
        this.onNoClickClick = onNoClickClick;
        this.onDebugDisplayClick = onDebugDisplayClick;
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

    public void setClickBlocked(boolean blocked) {
        mainHandler.post(() -> updateClickModeColor(blocked));
    }

    public void setGameEndedPaused(boolean paused) {
        mainHandler.post(() -> {
            gameEndedPaused = paused;
            updateClickModeColor(paused || false);
        });
    }

    public void setDebugDisplayEnabled(boolean enabled) {
        mainHandler.post(() -> {
            if (debugDisplayButton != null) {
                debugDisplayButton.setText(enabled ? "关调试" : "调试显示");
            }
        });
    }

    public void dismiss() {
        mainHandler.post(() -> {
            if (windowManager == null || rootView == null) {
                return;
            }
            try {
                windowManager.removeView(rootView);
            } catch (IllegalArgumentException ignored) {
            }
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
                WindowManager.LayoutParams.WRAP_CONTENT,
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
        root.setOnTouchListener((view, event) -> handleDrag(event));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        statusTitleView = new TextView(context);
        statusTitleView.setText("运行状态");
        statusTitleView.setTextSize(14f);
        statusTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        updateClickModeColor(false);
        header.addView(statusTitleView, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));

        debugDisplayButton = makeSmallButton("调试显示");
        debugDisplayButton.setOnClickListener(v -> onDebugDisplayClick.run());
        LinearLayout.LayoutParams debugParams = new LinearLayout.LayoutParams(dp(86), dp(32));
        debugParams.setMargins(0, 0, dp(6), 0);
        header.addView(debugDisplayButton, debugParams);

        collapseButton = makeSmallButton("折叠");
        collapseButton.setOnClickListener(v -> setCollapsed(!collapsed));
        header.addView(collapseButton, new LinearLayout.LayoutParams(dp(66), dp(32)));

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        contentView = new LinearLayout(context);
        contentView.setOrientation(LinearLayout.VERTICAL);

        parameterView = new LinearLayout(context);
        parameterView.setOrientation(LinearLayout.VERTICAL);
        parameterView.setVisibility(View.GONE);

        statusView = new TextView(context);
        statusView.setText(statusText);
        statusView.setTextColor(Color.rgb(225, 232, 240));
        statusView.setTextSize(12f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                dp(220),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(4), 0, dp(8));
        parameterView.addView(statusView, statusParams);
        contentView.addView(parameterView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        previewButton = makeSmallButton("开启预览");
        previewButton.setOnClickListener(v -> onPreviewClick.run());
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        previewParams.setMargins(0, dp(6), dp(6), 0);
        row.addView(previewButton, previewParams);

        noClickButton = makeSmallButton("不点击");
        noClickButton.setOnClickListener(v -> onNoClickClick.run());
        LinearLayout.LayoutParams noClickParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        noClickParams.setMargins(0, dp(6), dp(6), 0);
        row.addView(noClickButton, noClickParams);

        detailsButton = makeSmallButton("显示参数");
        detailsButton.setOnClickListener(v -> setParametersVisible(!parametersVisible));
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        detailsParams.setMargins(0, dp(6), dp(6), 0);
        row.addView(detailsButton, detailsParams);

        Button stop = makeSmallButton("停止");
        stop.setOnClickListener(v -> onStopClick.run());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        stopParams.setMargins(0, dp(6), 0, 0);
        row.addView(stop, stopParams);

        contentView.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(contentView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private Button makeSmallButton(String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        return button;
    }

    private void setParametersVisible(boolean visible) {
        parametersVisible = visible;
        if (parameterView != null) {
            parameterView.setVisibility(visible && !collapsed ? View.VISIBLE : View.GONE);
        }
        if (detailsButton != null) {
            detailsButton.setText(visible ? "隐藏参数" : "显示参数");
        }
        updateLayout();
    }

    private void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        if (contentView != null) {
            contentView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (parameterView != null) {
            parameterView.setVisibility(!collapsed && parametersVisible ? View.VISIBLE : View.GONE);
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
    }

    private void updateClickModeColor(boolean noClickMode) {
        if (statusTitleView != null) {
            int color;
            if (gameEndedPaused) {
                color = Color.rgb(255, 194, 87);
            } else if (noClickMode) {
                color = Color.rgb(255, 102, 102);
            } else {
                color = Color.rgb(94, 232, 142);
            }
            statusTitleView.setTextColor(color);
        }
    }

    private void updateLayout() {
        if (windowManager != null && rootView != null && params != null) {
            try {
                windowManager.updateViewLayout(rootView, params);
            } catch (IllegalArgumentException ignored) {
            }
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
                return true;

            default:
                return false;
        }
    }

    private void clearViews() {
        rootView = null;
        contentView = null;
        parameterView = null;
        statusTitleView = null;
        statusView = null;
        collapseButton = null;
        detailsButton = null;
        previewButton = null;
        noClickButton = null;
        debugDisplayButton = null;
        params = null;
        parametersVisible = false;
        collapsed = false;
        gameEndedPaused = false;
    }

    private GradientDrawable makeBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(222, 24, 28, 36));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), Color.argb(120, 255, 255, 255));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
