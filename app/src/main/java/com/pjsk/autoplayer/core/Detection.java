package com.pjsk.autoplayer.core;

public final class Detection {
    public static final int CLS_TAP = 0;
    public static final int CLS_HOLD = 1;
    public static final int CLS_SWEEP = 2;
    public static final int CLS_FLICK = 3;

    public final float x1;
    public final float y1;
    public final float x2;
    public final float y2;
    public final float x;
    public final float y;
    public final int cls;
    public final float confidence;

    public Detection(float x1, float y1, float x2, float y2, int cls, float confidence) {
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.cls = cls;
        this.confidence = confidence;
        this.x = (x1 + x2) * 0.5f;
        this.y = cls == CLS_HOLD ? y2 : (y1 + y2) * 0.5f;
    }
}
