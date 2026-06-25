package com.pjsk.autoplayer.core;

import android.util.Log;

import com.pjsk.autoplayer.input.TouchInjector;
import com.pjsk.autoplayer.input.TouchPoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AutoPlayer {
    private static final String TAG = "PJSK-AutoPlayer";

    public interface ActionListener {
        void onAction(String action, int x, int y);
    }

    private final LaneTracker tracker = new LaneTracker();
    private final TouchInjector injector;
    private final ActionListener actionListener;
    private final Map<Integer, NoteState> noteStates = new HashMap<>();
    private final List<Integer> availableTouchIds = new ArrayList<>();
    private double pxScale = 1.0;
    private double displayScaleX = 1.0;
    private double displayScaleY = 1.0;
    private double actionYBase = Config.ACTION_Y_DEFAULT;
    private boolean clickEnabled = true;

    public AutoPlayer(TouchInjector injector) {
        this(injector, null);
    }

    public AutoPlayer(TouchInjector injector, ActionListener actionListener) {
        this.injector = injector;
        this.actionListener = actionListener;
        for (int i = 0; i < 10; i++) {
            availableTouchIds.add(i);
        }
    }

    public void onFrame(
            List<Detection> detections,
            int frameW,
            int frameH,
            int displayW,
            int displayH,
            double timestampSec) {
        pxScale = Config.scaleForFrame(frameW);
        displayScaleX = displayW / (double) Math.max(1, frameW);
        displayScaleY = displayH / (double) Math.max(1, frameH);
        List<NoteTrack> confirmed = tracker.update(detections, frameW, frameH, timestampSec);
        processAutoAction(confirmed);
    }

    public void setActionYBase(double actionYBase) {
        this.actionYBase = Config.ACTION_Y_MIN <= actionYBase && actionYBase <= Config.ACTION_Y_MAX
                ? actionYBase
                : Config.ACTION_Y_DEFAULT;
    }

    public void setClickEnabled(boolean clickEnabled) {
        if (this.clickEnabled && !clickEnabled) {
            releaseAllActiveTouches();
        }
        this.clickEnabled = clickEnabled;
    }

    private void processAutoAction(List<NoteTrack> confirmedTracks) {
        double actionY = s(actionYBase);
        List<PendingRelease> pendingTapReleases = new ArrayList<>();
        List<TouchPoint> pendingFlicks = new ArrayList<>();

        rebindHoldingStates(confirmedTracks);
        Set<Integer> activeIds = new HashSet<>();
        for (NoteTrack t : confirmedTracks) {
            activeIds.add(t.id);
        }
        cleanupStaleStates(activeIds);

        for (NoteTrack trk : confirmedTracks) {
            NoteState ns = noteStates.get(trk.id);
            if (ns == null) {
                ns = new NoteState(trk.id);
                noteStates.put(trk.id, ns);
            }

            if (ns.state == NoteState.STATE_WAITING) {
                if (isHoldFragment(trk)) {
                    continue;
                }

                double triggerY = actionY;
                double clickLineMargin = s(Config.CLICK_LINE_MARGIN);
                boolean immediateFlick = trk.cls == Detection.CLS_FLICK;
                boolean crossedLine = trk.prevY <= triggerY && triggerY <= trk.y
                        && Math.abs(trk.y - triggerY) <= clickLineMargin * 1.5;
                boolean nearLine = triggerY - clickLineMargin <= trk.y
                        && trk.y <= triggerY + clickLineMargin;

                if (immediateFlick || crossedLine || nearLine) {
                    if (!clickEnabled) {
                        ns.state = NoteState.STATE_FINISHED;
                        continue;
                    }

                    int touchId = acquireTouchId();
                    if (touchId < 0) {
                        Log.w(TAG, "touch id exhausted");
                        continue;
                    }

                    if (trk.cls == Detection.CLS_FLICK) {
                        double clickX = clickXAtLine(trk, actionY);
                        int touchX = toDisplayX(clickX);
                        int touchY = toDisplayY(actionY);
                        Log.i(TAG, "flick x=" + touchX + " y=" + touchY
                                + " touchId=" + touchId);
                        reportAction("flick", touchX, touchY);
                        pendingFlicks.add(new TouchPoint(touchX, touchY, touchId));
                        ns.state = NoteState.STATE_FINISHED;
                    } else if (trk.cls == Detection.CLS_HOLD) {
                        ns.touchId = touchId;
                        ns.x = trk.x;
                        ns.y = trk.y;
                        double clickX = clickXAtLine(trk, actionY);
                        ns.lastMoveX = clickX;
                        int touchX = toDisplayX(clickX);
                        int touchY = toDisplayY(actionY);
                        Log.i(TAG, "hold down x=" + touchX + " y=" + touchY
                                + " touchId=" + touchId);
                        reportAction("hold", touchX, touchY);
                        injector.down(touchX, touchY, touchId);
                        ns.state = NoteState.STATE_HOLDING;
                    } else {
                        ns.touchId = touchId;
                        double clickX = clickXAtLine(trk, actionY);
                        int touchX = toDisplayX(clickX);
                        int touchY = toDisplayY(actionY);
                        Log.i(TAG, "tap x=" + touchX + " y=" + touchY
                                + " touchId=" + touchId);
                        reportAction("tap", touchX, touchY);
                        injector.down(touchX, touchY, touchId);
                        pendingTapReleases.add(new PendingRelease(ns));
                    }
                }
            } else if (ns.state == NoteState.STATE_HOLDING) {
                ns.y = trk.y;
                if (trk.missed > 0) {
                    ns.missingSeconds += tracker.frameDt;
                } else {
                    ns.missingSeconds = 0.0;
                }

                if (ns.missingSeconds >= Config.HOLD_LOST_SECONDS) {
                    releaseNote(ns);
                    continue;
                }

                if (trk.cls == Detection.CLS_FLICK) {
                    if (ns.touchId >= 0) {
                        double clickX = clickXAtLine(trk, actionY);
                        int touchX = toDisplayX(clickX);
                        int touchY = toDisplayY(actionY);
                        Log.i(TAG, "hold flick x=" + touchX + " y=" + touchY
                                + " touchId=" + ns.touchId);
                        reportAction("hold_flick", touchX, touchY);
                        injector.flickHeld(touchX, touchY, ns.touchId);
                        releaseTouchId(ns.touchId);
                        ns.touchId = -1;
                    }
                    ns.state = NoteState.STATE_FINISHED;
                    continue;
                }

                boolean tailNearLine = trk.y >= actionY - s(Config.HOLD_TAIL_Y_MARGIN);
                if (trk.cls == Detection.CLS_TAP && tailNearLine) {
                    ns.tailSeconds += tracker.frameDt;
                } else {
                    ns.tailSeconds = 0.0;
                }

                if (ns.tailSeconds >= Config.HOLD_TAIL_CONFIRM_SECONDS) {
                    releaseNote(ns);
                    continue;
                }

                if (ns.touchId >= 0) {
                    ns.x += (trk.x - ns.x) * Config.HOLD_MOVE_SMOOTHING;
                    double moveX = tracker.projectXToY(ns.x, ns.y, actionY);
                    if (Double.isNaN(ns.lastMoveX)
                            || Math.abs(moveX - ns.lastMoveX) >= s(Config.HOLD_MOVE_DEADZONE)) {
                        injector.move(toDisplayX(moveX), toDisplayY(actionY), ns.touchId);
                        ns.lastMoveX = moveX;
                    }
                }
            }
        }

        for (PendingRelease release : pendingTapReleases) {
            releaseNote(release.noteState);
        }
        if (!pendingFlicks.isEmpty()) {
            injector.flickBatch(pendingFlicks);
            for (TouchPoint point : pendingFlicks) {
                releaseTouchId(point.touchId);
            }
        }
    }

    private void rebindHoldingStates(List<NoteTrack> confirmedTracks) {
        Map<Integer, NoteTrack> tracksById = new HashMap<>();
        List<NoteTrack> candidates = new ArrayList<>();
        for (NoteTrack trk : confirmedTracks) {
            tracksById.put(trk.id, trk);
            NoteState ns = noteStates.get(trk.id);
            boolean waitingOrNew = ns == null || ns.state == NoteState.STATE_WAITING;
            if (waitingOrNew
                    && (trk.cls == Detection.CLS_TAP
                    || trk.cls == Detection.CLS_HOLD
                    || trk.cls == Detection.CLS_SWEEP
                    || trk.cls == Detection.CLS_FLICK)
                    && trk.y >= s(actionYBase) - s(Config.HOLD_REBIND_Y_MARGIN)) {
                candidates.add(trk);
            }
        }

        List<Integer> oldIds = new ArrayList<>(noteStates.keySet());
        for (int oldId : oldIds) {
            NoteState ns = noteStates.get(oldId);
            if (ns == null || ns.state != NoteState.STATE_HOLDING || tracksById.containsKey(oldId)) {
                continue;
            }
            NoteTrack best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (NoteTrack trk : candidates) {
                double distance = Math.abs(trk.x - ns.x);
                int candidateKey = tracker.getKeyAt(trk.x, trk.y);
                int holdKey = tracker.getKeyAt(ns.x, ns.y);
                boolean sameKey = candidateKey >= 0 && candidateKey == holdKey;
                double maxDistance = (trk.cls == Detection.CLS_TAP || trk.cls == Detection.CLS_FLICK)
                        ? s(Config.HOLD_TAIL_REBIND_X)
                        : s(Config.HOLD_REBIND_X);
                boolean tailCandidate = trk.cls == Detection.CLS_TAP || trk.cls == Detection.CLS_FLICK;
                boolean tailReady = !tailCandidate
                        || (ns.missingSeconds > 0.0
                        && sameKey
                        && Math.abs(trk.y - ns.y) <= s(Config.HOLD_TAIL_REBIND_Y));
                if (tailReady && distance <= maxDistance && distance < bestDistance) {
                    best = trk;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                continue;
            }

            noteStates.remove(oldId);
            noteStates.remove(best.id);
            ns.trackId = best.id;
            ns.missingSeconds = 0.0;
            ns.tailSeconds = 0.0;
            noteStates.put(best.id, ns);
            candidates.remove(best);
        }
    }

    private void cleanupStaleStates(Set<Integer> activeTrackIds) {
        List<Integer> staleIds = new ArrayList<>();
        for (int id : noteStates.keySet()) {
            if (!activeTrackIds.contains(id)) {
                staleIds.add(id);
            }
        }
        for (int id : staleIds) {
            NoteState ns = noteStates.get(id);
            if (ns == null) {
                continue;
            }
            if (ns.state == NoteState.STATE_HOLDING) {
                ns.missingSeconds += tracker.frameDt;
                if (ns.missingSeconds >= Config.HOLD_LOST_SECONDS) {
                    releaseNote(ns);
                    noteStates.remove(id);
                }
            } else {
                noteStates.remove(id);
            }
        }
    }

    private boolean isHoldFragment(NoteTrack trk) {
        for (NoteState ns : noteStates.values()) {
            if (ns.state != NoteState.STATE_HOLDING || ns.trackId == trk.id) {
                continue;
            }
            if (ns.missingSeconds <= 0.0) {
                continue;
            }
            int candidateKey = tracker.getKeyAt(trk.x, trk.y);
            int holdKey = tracker.getKeyAt(ns.x, ns.y);
            boolean sameKey = candidateKey >= 0 && candidateKey == holdKey;
            if (sameKey
                    && Math.abs(trk.x - ns.x) <= s(Config.HOLD_TAIL_REBIND_X)
                    && Math.abs(trk.y - ns.y) <= s(Config.HOLD_TAIL_REBIND_Y)) {
                return true;
            }
        }
        return false;
    }

    private int acquireTouchId() {
        if (availableTouchIds.isEmpty()) {
            return -1;
        }
        return availableTouchIds.remove(0);
    }

    private void releaseTouchId(int touchId) {
        if (touchId < 0 || availableTouchIds.contains(touchId)) {
            return;
        }
        int index = 0;
        while (index < availableTouchIds.size() && availableTouchIds.get(index) < touchId) {
            index++;
        }
        availableTouchIds.add(index, touchId);
    }

    private void releaseNote(NoteState ns) {
        if (ns.touchId >= 0) {
            Log.i(TAG, "release touchId=" + ns.touchId);
            injector.up(ns.touchId);
            releaseTouchId(ns.touchId);
            ns.touchId = -1;
        }
        ns.state = NoteState.STATE_FINISHED;
    }

    private void releaseAllActiveTouches() {
        for (NoteState ns : noteStates.values()) {
            if (ns.touchId >= 0) {
                Log.i(TAG, "no click mode release touchId=" + ns.touchId);
                injector.up(ns.touchId);
                releaseTouchId(ns.touchId);
                ns.touchId = -1;
            }
            ns.state = NoteState.STATE_FINISHED;
        }
    }

    private double s(double value) {
        return value * pxScale;
    }

    private double clickXAtLine(NoteTrack trk, double actionY) {
        return tracker.projectXToY(trk.x, trk.y, actionY);
    }

    private int toDisplayX(double x) {
        return (int) Math.round(x * displayScaleX);
    }

    private int toDisplayY(double y) {
        return (int) Math.round(y * displayScaleY);
    }

    private void reportAction(String action, int x, int y) {
        if (actionListener != null) {
            actionListener.onAction(action, x, y);
        }
    }

    private static final class PendingRelease {
        final NoteState noteState;

        PendingRelease(NoteState noteState) {
            this.noteState = noteState;
        }
    }

    private static final class NoteState {
        static final int STATE_WAITING = 0;
        static final int STATE_HOLDING = 1;
        static final int STATE_FINISHED = 2;

        int trackId;
        int state = STATE_WAITING;
        int touchId = -1;
        double missingSeconds;
        double tailSeconds;
        double x;
        double y;
        double lastMoveX = Double.NaN;

        NoteState(int trackId) {
            this.trackId = trackId;
        }
    }
}
