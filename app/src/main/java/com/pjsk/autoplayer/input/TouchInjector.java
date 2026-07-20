package com.pjsk.autoplayer.input;

import java.util.List;

public interface TouchInjector {
    void down(int x, int y, int touchId);

    void move(int x, int y, int touchId);

    void up(int touchId);

    /**
     * A timeline-driven chart needs its input emitted by the timeline clock,
     * rather than queued behind detector actions. Injectors without a direct
     * path can safely fall back to their regular implementation.
     */
    default void downRealtime(int x, int y, int touchId) {
        down(x, y, touchId);
    }

    default void moveRealtime(int x, int y, int touchId) {
        move(x, y, touchId);
    }

    default void upRealtime(int touchId) {
        up(touchId);
    }

    void flickBatch(List<TouchPoint> points);

    void flickHeld(int x, int y, int touchId);

    void shutdown();
}
