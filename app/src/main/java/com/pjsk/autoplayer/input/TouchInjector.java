package com.pjsk.autoplayer.input;

import java.util.List;

public interface TouchInjector {
    void down(int x, int y, int touchId);

    void move(int x, int y, int touchId);

    void up(int touchId);

    void flickBatch(List<TouchPoint> points);

    void flickHeld(int x, int y, int touchId);

    void shutdown();
}
