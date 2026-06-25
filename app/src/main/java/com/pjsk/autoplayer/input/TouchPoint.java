package com.pjsk.autoplayer.input;

public final class TouchPoint {
    public final int x;
    public final int y;
    public final int touchId;

    public TouchPoint(int x, int y, int touchId) {
        this.x = x;
        this.y = y;
        this.touchId = touchId;
    }
}
