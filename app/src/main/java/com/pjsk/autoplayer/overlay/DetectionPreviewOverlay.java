package com.pjsk.autoplayer.overlay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;

import com.pjsk.autoplayer.core.Config;
import com.pjsk.autoplayer.core.Detection;
import com.pjsk.autoplayer.settings.AppSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class DetectionPreviewOverlay {
    private static final String TAG = "PJSK-PreviewOverlay";
    private static final long PREVIEW_INTERVAL_MS = 83;
    private static final int PREVIEW_WIDTH_DP = 280;
    private static final int PREVIEW_HEIGHT_DP = 158;
    private static final int COLOR_PANEL = Color.rgb(22, 28, 39);
    private static final int COLOR_PANEL_BORDER = Color.rgb(164, 183, 211);
    private static final int COLOR_BUTTON = Color.rgb(44, 53, 68);
    private static final int COLOR_BUTTON_BORDER = Color.rgb(84, 99, 122);
    private static final int COLOR_BUTTON_TEXT = Color.rgb(238, 244, 250);
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private LinearLayout rootView;
    private PreviewView previewView;
    private long lastPreviewMs;
    private int anchorX;
    private int anchorY;

    private int startX;
    private int startY;
    private float downRawX;
    private float downRawY;

    public DetectionPreviewOverlay(Context context) {
        this.context = context.getApplicationContext();
        anchorX = dp(258);
        anchorY = 0;
    }

    public boolean isShown() {
        return rootView != null;
    }

    public void show() {
        mainHandler.post(this::showOnMain);
    }

    /** Places this independent preview window beside the control overlay. */
    public void setAnchorPosition(int x, int y) {
        mainHandler.post(() -> {
            anchorX = Math.max(0, x);
            anchorY = Math.max(0, y);
            if (windowManager == null || rootView == null || params == null) {
                return;
            }
            try {
                params.x = anchorX;
                params.y = anchorY;
                windowManager.updateViewLayout(rootView, params);
            } catch (IllegalArgumentException ignored) {
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
            if (previewView != null) {
                previewView.clear();
            }
            rootView = null;
            previewView = null;
            params = null;
        });
    }

    public void updateFrame(
            Bitmap frame,
            List<Detection> detections,
            int frameWidth,
            int frameHeight,
            double fps,
            long inferenceMs,
            int droppedFrames,
            double actionYBase) {
        updateFrame(
                frame,
                detections,
                frameWidth,
                frameHeight,
                fps,
                inferenceMs,
                droppedFrames,
                actionYBase,
                false);
    }

    public void updateFrame(
            Bitmap frame,
            List<Detection> detections,
            int frameWidth,
            int frameHeight,
            double fps,
            long inferenceMs,
            int droppedFrames,
            double actionYBase,
            boolean buttonLabels) {
        if (rootView == null || frame == null || frame.isRecycled()) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastPreviewMs < PREVIEW_INTERVAL_MS) {
            return;
        }
        lastPreviewMs = now;

        Bitmap previewBitmap = makePreviewBitmap(frame, frameWidth, frameHeight);
        List<Box> boxes = copyBoxes(detections);
        String stats = String.format(
                Locale.US,
                "FPS %.1f  infer %dms  boxes %d  drop %d  line %.0f",
                fps,
                inferenceMs,
                boxes.size(),
                droppedFrames,
                actionYBase);

        mainHandler.post(() -> {
            if (previewView != null) {
                previewView.setFrame(
                        previewBitmap,
                        boxes,
                        frameWidth,
                        frameHeight,
                        actionYBase,
                        stats,
                        buttonLabels);
            } else {
                previewBitmap.recycle();
            }
        });
    }

    private void showOnMain() {
        if (!StatusOverlay.canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission is not granted");
            return;
        }
        if (rootView != null) {
            return;
        }

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "WindowManager is null");
            return;
        }

        rootView = buildView();
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.START | Gravity.TOP;
        params.x = anchorX;
        params.y = anchorY;

        try {
            windowManager.addView(rootView, params);
        } catch (RuntimeException e) {
            Log.e(TAG, "failed to show preview overlay", e);
            rootView = null;
            previewView = null;
            params = null;
        }
    }

    private LinearLayout buildView() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(6), dp(6), dp(6));
        root.setBackground(makeBackground());
        root.setOnTouchListener((view, event) -> handleDrag(event));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setVisibility(View.GONE);

        TextView title = new TextView(context);
        title.setText("识别预览");
        title.setTextColor(Color.rgb(151, 210, 255));
        title.setTextSize(13f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f));

        Button close = new Button(context);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setMinHeight(0);
        close.setMinimumHeight(0);
        close.setTextSize(11f);
        close.setTextColor(COLOR_BUTTON_TEXT);
        close.setPadding(dp(4), 0, dp(4), 0);
        close.setBackground(makeButtonBackground(COLOR_BUTTON, COLOR_BUTTON_BORDER));
        close.setOnClickListener(v -> {});
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(30)));

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        previewView = new PreviewView(context, this::setActionYFromPreview);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                dp(PREVIEW_WIDTH_DP),
                dp(PREVIEW_HEIGHT_DP));
        root.addView(previewView, previewParams);

        return root;
    }

    private void setActionYFromPreview(double actionYBase) {
        if (AppSettings.isActionYCalibrationLocked(context)) {
            return;
        }
        AppSettings.setActionY(context, actionYBase);
        if (previewView != null) {
            previewView.setActionYBase(AppSettings.getActionY(context));
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
                try {
                    windowManager.updateViewLayout(rootView, params);
                } catch (IllegalArgumentException ignored) {
                }
                return true;

            default:
                return false;
        }
    }

    private Bitmap makePreviewBitmap(Bitmap frame, int frameWidth, int frameHeight) {
        int maxWidth = dp(PREVIEW_WIDTH_DP);
        int maxHeight = dp(PREVIEW_HEIGHT_DP);
        float aspect = frameWidth / (float) Math.max(1, frameHeight);
        int targetWidth = maxWidth;
        int targetHeight = Math.max(1, Math.round(maxWidth / aspect));
        if (targetHeight > maxHeight) {
            targetHeight = maxHeight;
            targetWidth = Math.max(1, Math.round(maxHeight * aspect));
        }
        return Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, false);
    }

    private List<Box> copyBoxes(List<Detection> detections) {
        if (detections == null || detections.isEmpty()) {
            return Collections.emptyList();
        }
        List<Box> boxes = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            boxes.add(new Box(
                    detection.x1,
                    detection.y1,
                    detection.x2,
                    detection.y2,
                    detection.x,
                    detection.y,
                    detection.cls,
                    detection.confidence));
        }
        return boxes;
    }

    private GradientDrawable makeBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(238, Color.red(COLOR_PANEL), Color.green(COLOR_PANEL), Color.blue(COLOR_PANEL)));
        drawable.setCornerRadius(dp(10));
        drawable.setStroke(dp(1), Color.argb(145,
                Color.red(COLOR_PANEL_BORDER),
                Color.green(COLOR_PANEL_BORDER),
                Color.blue(COLOR_PANEL_BORDER)));
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

    private static final class Box {
        final float x1;
        final float y1;
        final float x2;
        final float y2;
        final float x;
        final float y;
        final int cls;
        final float confidence;

        Box(float x1, float y1, float x2, float y2, float x, float y, int cls, float confidence) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.x = x;
            this.y = y;
            this.cls = cls;
            this.confidence = confidence;
        }
    }

    private final class PreviewView extends View {
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect sourceRect = new Rect();
        private final RectF imageRect = new RectF();
        private final RectF boxRect = new RectF();

        private Bitmap frame;
        private List<Box> boxes = Collections.emptyList();
        private int frameWidth = 1;
        private int frameHeight = 1;
        private double actionYBase = Config.ACTION_Y_DEFAULT;
        private String stats = "waiting";
        private boolean buttonLabels;
        private final CalibrationListener calibrationListener;

        PreviewView(Context context, CalibrationListener calibrationListener) {
            super(context);
            this.calibrationListener = calibrationListener;
            setClickable(true);
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(3f);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(24f);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            fillPaint.setStyle(Paint.Style.FILL);
        }

        void setFrame(
                Bitmap frame,
                List<Box> boxes,
                int frameWidth,
                int frameHeight,
                double actionYBase,
                String stats,
                boolean buttonLabels) {
            Bitmap oldFrame = this.frame;
            this.frame = frame;
            this.boxes = boxes;
            this.frameWidth = Math.max(1, frameWidth);
            this.frameHeight = Math.max(1, frameHeight);
            this.actionYBase = AppSettings.clampActionY(actionYBase);
            this.stats = stats;
            this.buttonLabels = buttonLabels;
            if (oldFrame != null && oldFrame != frame && !oldFrame.isRecycled()) {
                oldFrame.recycle();
            }
            invalidate();
        }

        void clear() {
            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }
            frame = null;
            boxes = Collections.emptyList();
        }

        void setActionYBase(double actionYBase) {
            this.actionYBase = AppSettings.clampActionY(actionYBase);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.rgb(5, 7, 11));

            if (frame == null || frame.isRecycled()) {
                drawStatus(canvas, "waiting frame");
                return;
            }

            sourceRect.set(0, 0, frame.getWidth(), frame.getHeight());
            fitImageRect();
            canvas.drawBitmap(frame, sourceRect, imageRect, bitmapPaint);
            drawActionLine(canvas);
            drawBoxes(canvas);
            drawStatus(canvas, stats);
        }

        private void fitImageRect() {
            float viewWidth = getWidth();
            float viewHeight = getHeight();
            float aspect = frameWidth / (float) frameHeight;
            float width = viewWidth;
            float height = width / aspect;
            if (height > viewHeight) {
                height = viewHeight;
                width = height * aspect;
            }
            float left = (viewWidth - width) * 0.5f;
            float top = (viewHeight - height) * 0.5f;
            imageRect.set(left, top, left + width, top + height);
        }

        private void drawActionLine(Canvas canvas) {
            float y = toViewY((float) (actionYBase * Config.scaleForFrame(frameWidth)));
            boxPaint.setColor(Color.argb(210, 255, 255, 255));
            boxPaint.setStrokeWidth(2f);
            canvas.drawLine(imageRect.left, y, imageRect.right, y, boxPaint);
        }

        private void drawBoxes(Canvas canvas) {
            for (Box box : boxes) {
                int color = colorForClass(box.cls);
                boxPaint.setColor(color);
                boxPaint.setStrokeWidth(3f);
                boxRect.set(
                        toViewX(box.x1),
                        toViewY(box.y1),
                        toViewX(box.x2),
                        toViewY(box.y2));
                canvas.drawRect(boxRect, boxPaint);

                fillPaint.setColor(color);
                canvas.drawCircle(toViewX(box.x), toViewY(box.y), 5f, fillPaint);

                fillPaint.setColor(Color.argb(185, 0, 0, 0));
                float labelLeft = boxRect.left;
                float labelTop = Math.max(imageRect.top, boxRect.top - 28f);
                canvas.drawRect(labelLeft, labelTop, labelLeft + 92f, labelTop + 26f, fillPaint);

                textPaint.setTextSize(20f);
                textPaint.setColor(Color.WHITE);
                canvas.drawText(labelForClass(box.cls) + " " + Math.round(box.confidence * 100f),
                        labelLeft + 5f,
                        labelTop + 20f,
                        textPaint);
            }
        }

        private void drawStatus(Canvas canvas, String text) {
            fillPaint.setColor(Color.argb(170, 0, 0, 0));
            canvas.drawRect(0, 0, getWidth(), 34f, fillPaint);
            textPaint.setTextSize(20f);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(text, 8f, 24f, textPaint);
        }

        private float toViewX(float x) {
            return imageRect.left + x / frameWidth * imageRect.width();
        }

        private float toViewY(float y) {
            return imageRect.top + y / frameHeight * imageRect.height();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (AppSettings.isActionYCalibrationLocked(getContext())) {
                // With the action line locked, the preview surface moves its own window.
                handleDrag(event);
                return true;
            }
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN
                    && event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                return true;
            }
            if (frameWidth <= 1 || frameHeight <= 1 || getWidth() <= 0 || getHeight() <= 0) {
                return true;
            }
            fitImageRect();
            if (!imageRect.contains(event.getX(), event.getY())) {
                return true;
            }
            float frameY = (event.getY() - imageRect.top) / Math.max(1f, imageRect.height())
                    * frameHeight;
            calibrationListener.onCalibrate(frameY / Config.scaleForFrame(frameWidth));
            return true;
        }

        private int colorForClass(int cls) {
            if (buttonLabels) {
                return cls == 1
                        ? Color.rgb(80, 232, 255)
                        : Color.rgb(255, 218, 92);
            }
            switch (cls) {
                case Detection.CLS_HOLD:
                    return Color.rgb(255, 210, 64);
                case Detection.CLS_SWEEP:
                    return Color.rgb(64, 206, 255);
                case Detection.CLS_FLICK:
                    return Color.rgb(245, 112, 255);
                case Detection.CLS_TAP:
                default:
                    return Color.rgb(86, 230, 148);
            }
        }

        private String labelForClass(int cls) {
            if (buttonLabels) {
                return cls == 1 ? "播放" : "确定";
            }
            switch (cls) {
                case Detection.CLS_HOLD:
                    return "hold";
                case Detection.CLS_SWEEP:
                    return "sweep";
                case Detection.CLS_FLICK:
                    return "flick";
                case Detection.CLS_TAP:
                default:
                    return "tap";
            }
        }
    }

    private interface CalibrationListener {
        void onCalibrate(double actionYBase);
    }
}
