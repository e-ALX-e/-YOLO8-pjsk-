package com.pjsk.autoplayer;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** A deliberately minimal landscape target for testing the floating overlay. */
public final class MainActivity extends Activity {
    // Kept for the unused capture service which is still compiled in this test branch.
    public static final String EXTRA_AUTO_REAUTHORIZE = "autoReauthorize";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(12, 17, 28));
        getWindow().setNavigationBarColor(Color.rgb(12, 17, 28));
        setContentView(new TouchFeedbackView());
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private final class TouchFeedbackView extends View {
        private static final long FEEDBACK_DURATION_MS = 520L;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SparseArray<ActiveTouch> activeTouches = new SparseArray<>();
        private final List<TouchFeedback> releasedTouches = new ArrayList<>();

        TouchFeedbackView() {
            super(MainActivity.this);
            // A fully black idle screen keeps OLED pixels unlit and avoids a fixed bright image.
            setBackgroundColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            long now = SystemClock.uptimeMillis();
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                activeTouches.put(pointerId, new ActiveTouch(
                        event.getX(pointerIndex), event.getY(pointerIndex), pointerColor(pointerId)));
                addReleaseRipple(event.getX(pointerIndex), event.getY(pointerIndex), now, pointerColor(pointerId));
            } else if (action == MotionEvent.ACTION_MOVE) {
                for (int index = 0; index < event.getPointerCount(); index++) {
                    int pointerId = event.getPointerId(index);
                    ActiveTouch touch = activeTouches.get(pointerId);
                    if (touch != null) {
                        touch.x = event.getX(index);
                        touch.y = event.getY(index);
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                ActiveTouch touch = activeTouches.get(pointerId);
                addReleaseRipple(event.getX(pointerIndex), event.getY(pointerIndex), now,
                        touch == null ? pointerColor(pointerId) : touch.color);
                activeTouches.remove(pointerId);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                for (int index = 0; index < event.getPointerCount(); index++) {
                    int pointerId = event.getPointerId(index);
                    ActiveTouch touch = activeTouches.get(pointerId);
                    addReleaseRipple(event.getX(index), event.getY(index), now,
                            touch == null ? pointerColor(pointerId) : touch.color);
                }
                activeTouches.clear();
            }
            invalidate();
            return true;
        }

        private void addReleaseRipple(float x, float y, long now, int color) {
            releasedTouches.add(new TouchFeedback(x, y, now, color));
            while (releasedTouches.size() > 24) {
                releasedTouches.remove(0);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long now = SystemClock.uptimeMillis();
            for (int index = 0; index < activeTouches.size(); index++) {
                ActiveTouch touch = activeTouches.valueAt(index);
                paint.setColor(touch.color);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(touch.x, touch.y, dp(22), paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(touch.x, touch.y, dp(8), paint);
            }

            Iterator<TouchFeedback> iterator = releasedTouches.iterator();
            boolean needsAnotherFrame = false;
            while (iterator.hasNext()) {
                TouchFeedback touch = iterator.next();
                float progress = (now - touch.createdAtMs) / (float) FEEDBACK_DURATION_MS;
                if (progress >= 1f) {
                    iterator.remove();
                    continue;
                }
                needsAnotherFrame = true;
                int alpha = Math.round((1f - progress) * 230f);
                paint.setColor(Color.argb(alpha,
                        Color.red(touch.color), Color.green(touch.color), Color.blue(touch.color)));
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(touch.x, touch.y, dp(18) + dp(54) * progress, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(touch.x, touch.y, dp(8) * (1f - progress * 0.5f), paint);
            }
            if (needsAnotherFrame) {
                postInvalidateOnAnimation();
            }
        }

        private int pointerColor(int pointerId) {
            switch (Math.floorMod(pointerId, 4)) {
                case 1:
                    return Color.rgb(255, 151, 212);
                case 2:
                    return Color.rgb(255, 207, 104);
                case 3:
                    return Color.rgb(171, 244, 156);
                case 0:
                default:
                    return Color.rgb(116, 231, 255);
            }
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }
    }

    private static final class TouchFeedback {
        final float x;
        final float y;
        final long createdAtMs;
        final int color;

        TouchFeedback(float x, float y, long createdAtMs, int color) {
            this.x = x;
            this.y = y;
            this.createdAtMs = createdAtMs;
            this.color = color;
        }
    }

    private static final class ActiveTouch {
        float x;
        float y;
        final int color;

        ActiveTouch(float x, float y, int color) {
            this.x = x;
            this.y = y;
            this.color = color;
        }
    }
}
