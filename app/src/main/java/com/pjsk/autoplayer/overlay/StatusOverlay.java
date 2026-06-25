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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class StatusOverlay {
    private static final String TAG = "PJSK-StatusOverlay";

    private final Context context;
    private final Runnable onStopClick;
    private final Runnable onPreviewClick;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout rootView;
    private TextView statusView;
    private Button previewButton;

    private int startX;
    private int startY;
    private float downRawX;
    private float downRawY;

    public StatusOverlay(Context context, Runnable onStopClick, Runnable onPreviewClick) {
        this.context = context.getApplicationContext();
        this.onStopClick = onStopClick;
        this.onPreviewClick = onPreviewClick;
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    public void show(String statusText) {
        mainHandler.post(() -> showOnMain(statusText));
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

    public void dismiss() {
        mainHandler.post(() -> {
            if (windowManager == null || rootView == null) {
                return;
            }
            try {
                windowManager.removeView(rootView);
            } catch (IllegalArgumentException ignored) {
            }
            rootView = null;
            statusView = null;
            previewButton = null;
            params = null;
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
        params.x = dp(16);
        params.y = dp(72);

        try {
            windowManager.addView(rootView, params);
        } catch (RuntimeException e) {
            Log.e(TAG, "failed to show overlay", e);
            rootView = null;
            statusView = null;
            previewButton = null;
            params = null;
        }
    }

    private LinearLayout buildView(String statusText) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.setBackground(makeBackground());
        root.setOnTouchListener((view, event) -> handleDrag(event));

        TextView title = new TextView(context);
        title.setText("运行状态");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(context);
        statusView.setText(statusText);
        statusView.setTextColor(Color.rgb(225, 232, 240));
        statusView.setTextSize(12f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                dp(220),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(4), 0, dp(8));
        root.addView(statusView, statusParams);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        previewButton = new Button(context);
        previewButton.setText("开启预览");
        previewButton.setAllCaps(false);
        previewButton.setMinHeight(0);
        previewButton.setMinimumHeight(0);
        previewButton.setPadding(dp(8), 0, dp(8), 0);
        previewButton.setOnClickListener(v -> onPreviewClick.run());
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
        previewParams.setMargins(0, 0, dp(6), 0);
        row.addView(previewButton, previewParams);

        Button stop = new Button(context);
        stop.setText("停止");
        stop.setAllCaps(false);
        stop.setMinHeight(0);
        stop.setMinimumHeight(0);
        stop.setPadding(dp(8), 0, dp(8), 0);
        stop.setOnClickListener(v -> onStopClick.run());
        row.addView(stop, new LinearLayout.LayoutParams(0, dp(38), 1f));

        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return root;
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
                try {
                    windowManager.updateViewLayout(rootView, params);
                } catch (IllegalArgumentException ignored) {
                }
                return true;

            default:
                return false;
        }
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
