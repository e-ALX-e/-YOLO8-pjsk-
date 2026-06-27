package com.pjsk.autoplayer.core;

public final class Config {
    private Config() {
    }

    public static final int MODEL_IMAGE_SIZE = 640;
    public static final float MODEL_CONFIDENCE = 0.60f;
    public static final float NMS_IOU = 0.45f;
    public static final double REFERENCE_FRAME_WIDTH = 720.0;

    public static final double REFLINE_TOP = 0.055;
    public static final double REFLINE_BOTTOM = 0.82;

    public static final double ACTION_Y_MIN = 190.0;
    public static final double ACTION_Y_MAX = 310.0;
    public static final double ACTION_Y_DEFAULT = 260.0;
    public static final double ACTION_Y = ACTION_Y_DEFAULT;
    public static final double CLICK_LINE_MARGIN = 28.0;
    public static final double HOLD_Y = 265.0;
    public static final double TRIGGER_MARGIN = 10.0;
    public static final double HOLD_LOST_SECONDS = 0.10;
    public static final double HOLD_REBIND_X = 70.0;
    public static final double HOLD_TAIL_REBIND_X = 20.0;
    public static final double HOLD_TAIL_REBIND_Y = 32.0;
    public static final double HOLD_REBIND_Y_MARGIN = 60.0;
    public static final double HOLD_MOVE_SMOOTHING = 0.45;
    public static final double HOLD_MOVE_DEADZONE = 3.0;
    public static final double HOLD_TAIL_Y_MARGIN = 24.0;
    public static final double HOLD_TAIL_CONFIRM_SECONDS = 0.025;
    public static final double VELOCITY_SMOOTHING = 0.55;
    public static final double FRAME_DT_SMOOTHING = 0.35;
    public static final double TRACK_LOST_SECONDS = 0.10;
    public static final double TRACK_DUPLICATE_SECONDS = 0.04;
    public static final double ACTION_LOOKAHEAD_SECONDS = 0.012;
    public static final double ACTION_MAX_LOOKAHEAD_PX = 24.0;

    public static final double FLICK_TRIGGER_ADVANCE_PX = 10.0;
    public static final double FLICK_LOOKAHEAD_SECONDS = 0.035;
    public static final double FLICK_MAX_LOOKAHEAD_PX = 70.0;
    public static final double FLICK_LATE_TRIGGER_PX = 80.0;
    public static final double FLICK_MISSING_PREDICT_SECONDS = 0.08;
    public static final double FLICK_HINT_SECONDS = 0.28;
    public static final double FLICK_HINT_X_MARGIN = 70.0;
    public static final double MIN_TAP_SECONDS = 0.026;
    public static final int FLICK_DISTANCE = 145;
    public static final int FLICK_STEPS = 4;
    public static final double FLICK_DOWN_SECONDS = 0.012;
    public static final double FLICK_STEP_SECONDS = 0.008;
    public static final double TOUCH_ID_RELEASE_DELAY_SECONDS = 0.060;
    public static final double FLICK_BASE_X_TOLERANCE = 36.0;
    public static final double FLICK_BASE_Y_TOLERANCE = 18.0;
    public static final double FLICK_BASE_LATE_X = 48.0;
    public static final double FLICK_BASE_LATE_Y = 180.0;

    public static double scaleForFrame(int frameWidth) {
        return Math.max(0.5, frameWidth / REFERENCE_FRAME_WIDTH);
    }
}
