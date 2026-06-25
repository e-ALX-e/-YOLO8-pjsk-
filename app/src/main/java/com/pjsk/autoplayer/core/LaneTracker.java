package com.pjsk.autoplayer.core;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LaneTracker {
    private static final String TAG = "PJSK-LaneTracker";
    private static final int NUM_SUB_LANES = 24;
    private static final int INIT_FRAMES = 2;
    private static final long FLICK_BASE_LOG_INTERVAL_MS = 1000;

    private final List<NoteTrack> tracks = new ArrayList<>();
    private final List<PendingDetection> pending = new ArrayList<>();
    private int nextId = 0;

    private double[] laneTopXs;
    private double[] laneBottomXs;
    private int frameW;
    private int frameH;
    private boolean initialized;
    private boolean globalVyInitialized;
    private double globalVy;
    private double pxScale = 1.0;
    private Double lastTimestamp;
    private long lastFlickBaseFallbackLogMs;
    public double frameDt = 1.0 / 60.0;

    public List<NoteTrack> update(List<Detection> detections, int width, int height, double timestamp) {
        pxScale = Config.scaleForFrame(width);
        updateFrameTime(timestamp);
        if (!initialized || width != frameW || height != frameH) {
            initializeLanes(width, height);
            tracks.clear();
            pending.clear();
            globalVyInitialized = false;
            globalVy = 0.0;
            lastTimestamp = timestamp;
        }

        List<PendingDetection> previousPending = new ArrayList<>(pending);
        pending.clear();

        List<BaseAnchor> globalBases = new ArrayList<>();
        for (Detection d : detections) {
            if (d.cls == Detection.CLS_TAP || d.cls == Detection.CLS_SWEEP) {
                globalBases.add(new BaseAnchor(d.x, d.y));
            }
        }
        globalBases.sort(Comparator.comparingDouble(b -> b.y));

        List<ProcessedDetection> processed = new ArrayList<>();
        for (Detection d : detections) {
            double anchorX = d.x;
            double anchorY = d.y;
            boolean hasFlickBase = d.cls != Detection.CLS_FLICK;
            if (d.cls == Detection.CLS_FLICK) {
                BaseAnchor bestBase = null;
                double minScore = Double.POSITIVE_INFINITY;
                LaneInfo lane = getLaneInfoAt(anchorX, anchorY);
                double halfKeyW = lane.localGap * 2.0;
                double maxXDist = halfKeyW * 1.2;
                double maxYDist = s(150.0);
                for (BaseAnchor base : globalBases) {
                    double dy = base.y - anchorY;
                    if (dy < -s(30.0) || dy > maxYDist) {
                        continue;
                    }
                    double dx = Math.abs(base.x - anchorX);
                    if (dx > maxXDist) {
                        continue;
                    }
                    double score = dy * 2.0 + dx;
                    if (score < minScore) {
                        minScore = score;
                        bestBase = base;
                    }
                }
                if (bestBase != null) {
                    anchorX = bestBase.x;
                    anchorY = bestBase.y;
                    hasFlickBase = true;
                } else {
                    anchorY = d.y2;
                    hasFlickBase = true;
                    logFlickBaseFallback();
                }
            }
            processed.add(new ProcessedDetection(anchorX, anchorY, d.cls, d.confidence, hasFlickBase));
        }

        List<ProcessedDetection> flickAnchors = new ArrayList<>();
        for (ProcessedDetection d : processed) {
            if (d.cls == Detection.CLS_FLICK) {
                flickAnchors.add(d);
            }
        }
        if (!flickAnchors.isEmpty()) {
            List<ProcessedDetection> filtered = new ArrayList<>();
            for (ProcessedDetection d : processed) {
                boolean pairedBase = d.cls == Detection.CLS_TAP || d.cls == Detection.CLS_SWEEP;
                if (pairedBase) {
                    boolean nearFlick = false;
                    for (ProcessedDetection flick : flickAnchors) {
                        if (flick.hasFlickBase
                                && Math.abs(d.y - flick.y) <= s(Config.FLICK_BASE_Y_TOLERANCE)
                                && Math.abs(d.x - flick.x) <= s(Config.FLICK_BASE_X_TOLERANCE)) {
                            nearFlick = true;
                            break;
                        }
                    }
                    pairedBase = nearFlick;
                }
                if (!pairedBase) {
                    filtered.add(d);
                }
            }
            processed = filtered;
        }
        processed.sort(Comparator.comparingDouble(d -> d.y));

        updateGlobalVelocity();

        List<MatchCandidate> candidates = new ArrayList<>();
        Set<Integer> matchedIds = new HashSet<>();
        Set<Integer> usedDetections = new HashSet<>();

        for (int ti = 0; ti < tracks.size(); ti++) {
            NoteTrack trk = tracks.get(ti);
            double predX;
            double predY;
            double absGvy;
            double yTolerance;
            double xTolerance;
            if (trk.cls == Detection.CLS_FLICK) {
                predX = trk.x + trk.vx * frameDt;
                predY = trk.y + trk.vy * frameDt;
                LaneInfo lane = getLaneInfoAt(predX, predY);
                absGvy = globalVyInitialized ? Math.abs(globalVy) : Math.abs(trk.vy);
                yTolerance = Math.max(s(35.0), absGvy * frameDt * 2.5);
                xTolerance = Math.max(s(20.0), lane.localGap * 1.5);
            } else {
                predX = trk.x;
                double velocityY = globalVyInitialized ? globalVy : trk.vy;
                predY = trk.y + velocityY * frameDt;
                LaneInfo lane = getLaneInfoAt(predX, predY);
                absGvy = globalVyInitialized ? Math.abs(globalVy) : Math.abs(trk.vy);
                yTolerance = Math.max(s(35.0), absGvy * frameDt * 2.5);
                xTolerance = Math.max(s(24.0), lane.localGap * 2.0);
            }

            for (int j = 0; j < processed.size(); j++) {
                ProcessedDetection det = processed.get(j);
                double absDy = Math.abs(det.y - predY);
                double absDx = Math.abs(det.x - predX);
                boolean delayedFlickBase = trk.cls == Detection.CLS_FLICK
                        && (det.cls == Detection.CLS_TAP || det.cls == Detection.CLS_SWEEP)
                        && 0.0 <= det.y - trk.y
                        && det.y - trk.y <= s(Config.FLICK_BASE_LATE_Y)
                        && absDx <= Math.max(xTolerance, s(Config.FLICK_BASE_LATE_X));
                if (!delayedFlickBase) {
                    if (absDy > yTolerance || absDx > xTolerance) {
                        continue;
                    }
                }

                double clsBonus = det.cls == trk.cls ? 0.0 : 45.0;
                if (delayedFlickBase) {
                    clsBonus = 5.0;
                }
                double score = absDy + absDx * 0.5 + clsBonus;
                candidates.add(new MatchCandidate(score, ti, j));
            }
        }
        candidates.sort(Comparator.comparingDouble(c -> c.score));

        for (MatchCandidate candidate : candidates) {
            NoteTrack trk = tracks.get(candidate.trackIndex);
            if (matchedIds.contains(trk.id) || usedDetections.contains(candidate.detectionIndex)) {
                continue;
            }
            ProcessedDetection det = processed.get(candidate.detectionIndex);
            boolean preserveFlick = trk.cls == Detection.CLS_FLICK
                    && (det.cls == Detection.CLS_TAP || det.cls == Detection.CLS_SWEEP);
            trk.update(det.x, det.y, det.cls, timestamp, preserveFlick);
            trk.confirmed = true;
            matchedIds.add(trk.id);
            usedDetections.add(candidate.detectionIndex);
        }

        for (NoteTrack trk : tracks) {
            if (!matchedIds.contains(trk.id)) {
                trk.missed++;
                trk.missedSeconds += frameDt;
            }
        }

        for (int j = 0; j < processed.size(); j++) {
            if (usedDetections.contains(j)) {
                continue;
            }
            ProcessedDetection det = processed.get(j);
            boolean found = false;
            for (PendingDetection old : previousPending) {
                LaneInfo lane = getLaneInfoAt(det.x, det.y);
                if (old.cls == det.cls
                        && Math.abs(old.y - det.y) < s(30.0)
                        && Math.abs(old.x - det.x) < Math.max(s(18.0), lane.localGap * 1.5)) {
                    pending.add(new PendingDetection(det.x, det.y, det.cls, old.count + 1));
                    found = true;
                    break;
                }
            }
            if (!found) {
                pending.add(new PendingDetection(det.x, det.y, det.cls, 1));
            }
        }

        tracks.removeIf(t -> t.missedSeconds > Config.TRACK_LOST_SECONDS);

        List<PendingDetection> newPending = new ArrayList<>();
        for (PendingDetection p : pending) {
            int requiredFrames = p.cls == Detection.CLS_FLICK ? 1 : INIT_FRAMES;
            if (p.count >= requiredFrames) {
                boolean alreadyTracked = false;
                double absGvy = globalVyInitialized ? Math.abs(globalVy) : 600.0;
                double dynamicYThresh = Math.max(s(30.0), absGvy * frameDt * 1.5);
                for (NoteTrack t : tracks) {
                    if (Math.abs(t.x - p.x) > s(30.0)) {
                        continue;
                    }
                    if (t.cls != p.cls || t.missedSeconds > Config.TRACK_DUPLICATE_SECONDS) {
                        continue;
                    }
                    if (Math.abs(t.y - p.y) < dynamicYThresh) {
                        alreadyTracked = true;
                        break;
                    }
                }
                if (!alreadyTracked) {
                    NoteTrack trk = new NoteTrack(nextId++, p.cls, p.x, p.y, timestamp);
                    if (p.cls == Detection.CLS_FLICK) {
                        trk.confirmed = true;
                    }
                    tracks.add(trk);
                }
            } else {
                newPending.add(p);
            }
        }
        pending.clear();
        pending.addAll(newPending);

        List<NoteTrack> confirmed = new ArrayList<>();
        for (NoteTrack t : tracks) {
            if (t.confirmed || t.age >= INIT_FRAMES) {
                confirmed.add(t);
            }
        }
        return confirmed;
    }

    public int getKeyAt(double x, double y) {
        LaneInfo info = getLaneInfoAt(x, y);
        if (info.subLane < 0 || info.distance > info.localGap * 1.2) {
            return -1;
        }
        return info.subLane / 2;
    }

    public double projectXToY(double x, double sourceY, double targetY) {
        LaneInfo info = getLaneInfoAt(x, sourceY);
        if (info.subLane < 0) {
            return x;
        }
        return getLaneXAt(info.subLane, targetY);
    }

    private void updateFrameTime(double timestamp) {
        if (lastTimestamp != null) {
            double rawDt = timestamp - lastTimestamp;
            if (rawDt >= 0.001 && rawDt <= 0.2) {
                frameDt = Config.FRAME_DT_SMOOTHING * rawDt
                        + (1.0 - Config.FRAME_DT_SMOOTHING) * frameDt;
            }
        }
        lastTimestamp = timestamp;
    }

    private void initializeLanes(int width, int height) {
        frameW = width;
        frameH = height;
        double halfW = width / 2.0;
        double topStart = halfW - halfW * Config.REFLINE_TOP;
        double topEnd = halfW + halfW * Config.REFLINE_TOP;
        double bottomStart = halfW - halfW * Config.REFLINE_BOTTOM;
        double bottomEnd = halfW + halfW * Config.REFLINE_BOTTOM;
        laneTopXs = new double[NUM_SUB_LANES];
        laneBottomXs = new double[NUM_SUB_LANES];
        for (int i = 0; i < NUM_SUB_LANES; i++) {
            double t0 = i / (double) NUM_SUB_LANES;
            double t1 = (i + 1) / (double) NUM_SUB_LANES;
            double top0 = topStart + (topEnd - topStart) * t0;
            double top1 = topStart + (topEnd - topStart) * t1;
            double bottom0 = bottomStart + (bottomEnd - bottomStart) * t0;
            double bottom1 = bottomStart + (bottomEnd - bottomStart) * t1;
            laneTopXs[i] = (top0 + top1) * 0.5;
            laneBottomXs[i] = (bottom0 + bottom1) * 0.5;
        }
        initialized = true;
    }

    private LaneInfo getLaneInfoAt(double x, double y) {
        if (!initialized || frameH <= 0) {
            return new LaneInfo(-1, x, 20.0, Double.POSITIVE_INFINITY);
        }
        double t = clamp(y / frameH, 0.0, 1.0);
        double bestDistance = Double.POSITIVE_INFINITY;
        int bestSub = -1;
        double bestX = x;
        double previousX = Double.NaN;
        double gapSum = 0.0;
        int gapCount = 0;
        for (int i = 0; i < NUM_SUB_LANES; i++) {
            double laneX = getLaneXAt(i, y);
            double dist = Math.abs(laneX - x);
            if (dist < bestDistance) {
                bestDistance = dist;
                bestSub = i;
                bestX = laneX;
            }
            if (!Double.isNaN(previousX)) {
                gapSum += Math.abs(laneX - previousX);
                gapCount++;
            }
            previousX = laneX;
        }
        double localGap = gapCount > 0 ? gapSum / gapCount : 20.0;
        return new LaneInfo(bestSub, bestX, localGap, bestDistance);
    }

    private double getLaneXAt(int subLane, double y) {
        if (!initialized || frameH <= 0 || subLane < 0 || subLane >= NUM_SUB_LANES) {
            return 0.0;
        }
        double t = clamp(y / frameH, 0.0, 1.0);
        return laneTopXs[subLane] * (1.0 - t) + laneBottomXs[subLane] * t;
    }

    private void updateGlobalVelocity() {
        List<Double> valid = new ArrayList<>();
        for (NoteTrack trk : tracks) {
            if (!trk.confirmed || trk.cls == Detection.CLS_FLICK) {
                continue;
            }
            if (Math.abs(trk.vy) < 80.0) {
                continue;
            }
            valid.add(trk.vy);
        }
        if (valid.size() >= 3) {
            double median = median(valid);
            double alpha = 0.3;
            if (globalVyInitialized) {
                globalVy = alpha * median + (1.0 - alpha) * globalVy;
            } else {
                globalVy = median;
                globalVyInitialized = true;
            }
        } else if (!globalVyInitialized && !valid.isEmpty()) {
            double sum = 0.0;
            for (double v : valid) {
                sum += v;
            }
            globalVy = sum / valid.size();
            globalVyInitialized = true;
        }
    }

    private static double median(List<Double> values) {
        List<Double> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int mid = copy.size() / 2;
        if (copy.size() % 2 == 1) {
            return copy.get(mid);
        }
        return (copy.get(mid - 1) + copy.get(mid)) * 0.5;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double s(double value) {
        return value * pxScale;
    }

    private void logFlickBaseFallback() {
        long now = System.currentTimeMillis();
        if (now - lastFlickBaseFallbackLogMs >= FLICK_BASE_LOG_INTERVAL_MS) {
            lastFlickBaseFallbackLogMs = now;
            Log.i(TAG, "using flick box bottom as base");
        }
    }

    private static final class LaneInfo {
        final int subLane;
        final double laneX;
        final double localGap;
        final double distance;

        LaneInfo(int subLane, double laneX, double localGap, double distance) {
            this.subLane = subLane;
            this.laneX = laneX;
            this.localGap = localGap;
            this.distance = distance;
        }
    }

    private static final class BaseAnchor {
        final double x;
        final double y;

        BaseAnchor(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class ProcessedDetection {
        final double x;
        final double y;
        final int cls;
        final double confidence;
        final boolean hasFlickBase;

        ProcessedDetection(double x, double y, int cls, double confidence, boolean hasFlickBase) {
            this.x = x;
            this.y = y;
            this.cls = cls;
            this.confidence = confidence;
            this.hasFlickBase = hasFlickBase;
        }
    }

    private static final class PendingDetection {
        final double x;
        final double y;
        final int cls;
        final int count;

        PendingDetection(double x, double y, int cls, int count) {
            this.x = x;
            this.y = y;
            this.cls = cls;
            this.count = count;
        }
    }

    private static final class MatchCandidate {
        final double score;
        final int trackIndex;
        final int detectionIndex;

        MatchCandidate(double score, int trackIndex, int detectionIndex) {
            this.score = score;
            this.trackIndex = trackIndex;
            this.detectionIndex = detectionIndex;
        }
    }
}
