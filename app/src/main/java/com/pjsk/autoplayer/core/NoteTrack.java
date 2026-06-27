package com.pjsk.autoplayer.core;

public final class NoteTrack {
    public final int id;
    public int cls;
    public double x;
    public double y;
    public double prevX;
    public double prevY;
    public double vx;
    public double vy;
    public double timestamp;
    public int age;
    public int missed;
    public double missedSeconds;
    public boolean confirmed;

    NoteTrack(int id, int cls, double x, double y, double timestamp) {
        this.id = id;
        this.cls = cls;
        this.x = x;
        this.y = y;
        this.prevX = x;
        this.prevY = y;
        this.timestamp = timestamp;
    }

    void update(double newX, double newY, int newCls, double newTimestamp, boolean preserveClass) {
        prevX = x;
        prevY = y;
        double dt = Math.max(newTimestamp - timestamp, 1e-3);
        double instantVx = (newX - x) / dt;
        double instantVy = (newY - y) / dt;
        if (age > 0) {
            vx = Config.VELOCITY_SMOOTHING * instantVx
                    + (1.0 - Config.VELOCITY_SMOOTHING) * vx;
            vy = Config.VELOCITY_SMOOTHING * instantVy
                    + (1.0 - Config.VELOCITY_SMOOTHING) * vy;
        } else {
            vx = instantVx;
            vy = instantVy;
        }
        x = newX;
        y = newY;
        timestamp = newTimestamp;
        if (!preserveClass) {
            cls = newCls;
        }
        missed = 0;
        missedSeconds = 0.0;
        age++;
    }

    void predict(double predictedX, double predictedY, double newTimestamp) {
        prevX = x;
        prevY = y;
        x = predictedX;
        y = predictedY;
        timestamp = newTimestamp;
    }
}
