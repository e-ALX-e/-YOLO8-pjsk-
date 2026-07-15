package com.pjsk.autoplayer.core;

import android.util.Log;

import com.pjsk.autoplayer.input.TouchInjector;
import com.pjsk.autoplayer.input.TouchPoint;

import java.util.ArrayList;
import java.util.Collections;
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
    private final Map<Integer, FlickHint> flickHints = new HashMap<>();
    private final List<Integer> availableTouchIds = new ArrayList<>();
    private final List<DelayedTouchRelease> delayedTouchReleases = new ArrayList<>();
    private final List<LogicEvent> logicEvents = new ArrayList<>();
    private final List<LogicGesture> activeLogicGestures = new ArrayList<>();
    private double pxScale = 1.0;
    private double displayScaleX = 1.0;
    private double displayScaleY = 1.0;
    private double actionYBase = Config.ACTION_Y_DEFAULT;
    private double currentTimestampSec;
    private int currentFrameW = (int) Config.REFERENCE_FRAME_WIDTH;
    private boolean clickEnabled = true;
    private boolean logicPlayEnabled;
    private boolean logicPlayActive;
    private int logicTapIntervalMs = 500;
    private double logicTapXRatio = 0.5;
    private double nextLogicTapAtSec = Double.NaN;
    private double logicStartedAtSec = Double.NaN;
    private int logicEventIndex;

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
        currentTimestampSec = timestampSec;
        currentFrameW = frameW;
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

    public void setLogicPlayConfig(boolean enabled, int tapIntervalMs, double tapXRatio) {
        setLogicPlayConfig(enabled, tapIntervalMs, tapXRatio, null);
    }

    public void setLogicPlayConfig(
            boolean enabled,
            int tapIntervalMs,
            double tapXRatio,
            List<LogicEvent> events) {
        if (logicPlayEnabled && !enabled) {
            resetLogicPlayRuntime();
        }
        logicPlayEnabled = enabled;
        logicTapIntervalMs = Math.max(80, Math.min(5000, tapIntervalMs));
        logicTapXRatio = Math.max(0.05, Math.min(0.95, tapXRatio));
        if (!sameLogicEvents(events)) {
            logicEvents.clear();
            if (events != null) {
                logicEvents.addAll(events);
            }
            if (logicPlayActive) {
                resetLogicPlayRuntime();
            }
        }
    }

    public boolean isLogicPlayActive() {
        return logicPlayEnabled && logicPlayActive;
    }

    public String logicPlayStatus() {
        if (!logicPlayEnabled) {
            return "\u5173";
        }
        if (!logicPlayActive) {
            return "\u7b49\u5f85\u9996\u97f3\u7b26";
        }
        if (logicEvents.isEmpty()) {
            return "\u70b9\u51fb\u4e2d";
        }
        if (logicEventIndex >= logicEvents.size() && activeLogicGestures.isEmpty()) {
            return "\u65f6\u95f4\u8f74\u5b8c\u6210";
        }
        return "\u65f6\u95f4\u8f74 " + logicEventIndex + "/" + logicEvents.size();
    }

    public void resetLogicPlayRuntime() {
        logicPlayActive = false;
        nextLogicTapAtSec = Double.NaN;
        logicStartedAtSec = Double.NaN;
        logicEventIndex = 0;
        releaseAllActiveTouches();
    }

    private void processAutoAction(List<NoteTrack> confirmedTracks) {
        double actionY = s(actionYBase);
        if (logicPlayEnabled && logicPlayActive) {
            processLogicPlay(actionY);
            return;
        }
        List<PendingRelease> pendingTapReleases = new ArrayList<>();
        List<TouchPoint> pendingFlicks = new ArrayList<>();

        releaseDueTouchIds();
        updateFlickHints(confirmedTracks, actionY);
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
                double lateTriggerMargin = trk.cls == Detection.CLS_FLICK
                        ? s(Config.FLICK_LATE_TRIGGER_PX)
                        : clickLineMargin * 1.5;
                boolean crossedLine = trk.prevY <= triggerY && triggerY <= trk.y
                        && trk.y <= triggerY + lateTriggerMargin;
                boolean nearLine = triggerY - clickLineMargin <= trk.y
                        && trk.y <= triggerY + lateTriggerMargin;

                if (crossedLine || nearLine) {
                    if (logicPlayEnabled) {
                        startLogicPlay(actionY);
                        processLogicPlay(actionY);
                        return;
                    }

                    if (!clickEnabled) {
                        ns.state = NoteState.STATE_FINISHED;
                        continue;
                    }

                    int touchId = acquireTouchId();
                    if (touchId < 0) {
                        Log.w(TAG, "touch id exhausted");
                        continue;
                    }

                    FlickHint flickHint = trk.cls == Detection.CLS_FLICK
                            ? null
                            : consumeFlickHint(trk, actionY);
                    boolean shouldFlick = trk.cls == Detection.CLS_FLICK || flickHint != null;

                    if (shouldFlick) {
                        double clickX = clickXAtLine(trk, actionY);
                        if (flickHint != null) {
                            clickX = flickHint.xAtLine;
                            Log.i(TAG, "flick hint used key=" + flickHint.key);
                        } else {
                            clearFlickHint(trk, actionY);
                        }
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

                boolean flickTailReady = trk.cls == Detection.CLS_FLICK
                        && trk.y >= actionY - s(Config.CLICK_LINE_MARGIN)
                        && trk.y <= actionY + s(Config.CLICK_LINE_MARGIN);
                if (flickTailReady) {
                    if (ns.touchId >= 0) {
                        double clickX = clickXAtLine(trk, actionY);
                        int touchX = toDisplayX(clickX);
                        int touchY = toDisplayY(actionY);
                        Log.i(TAG, "hold flick x=" + touchX + " y=" + touchY
                                + " touchId=" + ns.touchId);
                        reportAction("hold_flick", touchX, touchY);
                        injector.flickHeld(touchX, touchY, ns.touchId);
                        scheduleTouchIdRelease(ns.touchId);
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
                scheduleTouchIdRelease(point.touchId);
            }
        }
    }


    private void startLogicPlay(double actionY) {
        logicPlayActive = true;
        nextLogicTapAtSec = currentTimestampSec;
        logicStartedAtSec = currentTimestampSec;
        logicEventIndex = 0;
        releaseAllActiveTouches();
        noteStates.clear();
        flickHints.clear();
        Log.i(TAG, "logic play started actionY=" + actionY
                + " intervalMs=" + logicTapIntervalMs
                + " xRatio=" + logicTapXRatio
                + " timelineEvents=" + logicEvents.size());
    }

    private void processLogicPlay(double actionY) {
        releaseDueTouchIds();
        updateActiveLogicGestures(actionY);
        if (!clickEnabled) {
            return;
        }
        if (!logicEvents.isEmpty()) {
            double elapsedMs = Math.max(0.0, currentTimestampSec - logicStartedAtSec) * 1000.0;
            int startedThisFrame = 0;
            while (logicEventIndex < logicEvents.size()
                    && logicEvents.get(logicEventIndex).timeMs <= elapsedMs
                    && startedThisFrame < 16) {
                if (!startLogicEvent(logicEvents.get(logicEventIndex), actionY)) {
                    break;
                }
                logicEventIndex++;
                startedThisFrame++;
            }
            return;
        }
        if (Double.isNaN(nextLogicTapAtSec) || currentTimestampSec >= nextLogicTapAtSec) {
            tapLogicAtRatio(logicTapXRatio, actionY, "logic");
            nextLogicTapAtSec = currentTimestampSec + logicTapIntervalMs / 1000.0;
        }
    }

    private boolean startLogicEvent(LogicEvent event, double actionY) {
        int touchId = acquireTouchId();
        if (touchId < 0) {
            Log.w(TAG, "logic event delayed: touch id exhausted type=" + event.type);
            return false;
        }
        int startX = toDisplayX(event.xRatio * Math.max(1, currentFrameW));
        int endX = toDisplayX(event.endXRatio * Math.max(1, currentFrameW));
        int touchY = toDisplayY(actionY);
        int endY = LogicEvent.TYPE_FLICK.equals(event.type)
                ? Math.max(0, toDisplayY(actionY - s(Config.FLICK_DISTANCE)))
                : touchY;
        if (LogicEvent.TYPE_TAP.equals(event.type)) {
            Log.i(TAG, "logic tap x=" + startX + " y=" + touchY + " touchId=" + touchId);
            reportAction("logic_tap", startX, touchY);
            injector.down(startX, touchY, touchId);
            injector.up(touchId);
            scheduleTouchIdRelease(touchId);
            return true;
        }

        Log.i(TAG, "logic " + event.type + " x=" + startX + "->" + endX
                + " y=" + touchY + " durationMs=" + event.durationMs + " touchId=" + touchId);
        reportAction("logic_" + event.type, startX, touchY);
        injector.down(startX, touchY, touchId);
        activeLogicGestures.add(new LogicGesture(
                touchId,
                event.type,
                startX,
                endX,
                touchY,
                endY,
                event.points,
                currentTimestampSec,
                event.durationMs / 1000.0));
        return true;
    }

    private void updateActiveLogicGestures(double actionY) {
        for (int index = activeLogicGestures.size() - 1; index >= 0; index--) {
            LogicGesture gesture = activeLogicGestures.get(index);
            double progress = gesture.durationSec <= 0.0
                    ? 1.0
                    : Math.max(0.0, Math.min(1.0,
                            (currentTimestampSec - gesture.startedAtSec) / gesture.durationSec));
            if (LogicEvent.TYPE_SWIPE.equals(gesture.type)) {
                int moveX = gesture.points.isEmpty()
                        ? (int) Math.round(gesture.startX + (gesture.endX - gesture.startX) * progress)
                        : logicPathXAtProgress(gesture, progress);
                injector.move(moveX, gesture.y, gesture.touchId);
            } else if (LogicEvent.TYPE_FLICK.equals(gesture.type)) {
                int moveY = (int) Math.round(gesture.y + (gesture.endY - gesture.y) * progress);
                injector.move(gesture.startX, moveY, gesture.touchId);
            }
            if (progress >= 1.0) {
                if (LogicEvent.TYPE_SWIPE.equals(gesture.type)) {
                    injector.move(gesture.endX, gesture.y, gesture.touchId);
                } else if (LogicEvent.TYPE_FLICK.equals(gesture.type)) {
                    injector.move(gesture.startX, gesture.endY, gesture.touchId);
                }
                injector.up(gesture.touchId);
                scheduleTouchIdRelease(gesture.touchId);
                activeLogicGestures.remove(index);
            }
        }
    }

    private void tapLogicAtRatio(double xRatio, double actionY, String action) {
        int touchId = acquireTouchId();
        if (touchId < 0) {
            Log.w(TAG, "logic tap skipped: touch id exhausted");
            return;
        }
        int touchX = toDisplayX(xRatio * Math.max(1, currentFrameW));
        int touchY = toDisplayY(actionY);
        Log.i(TAG, "logic tap x=" + touchX + " y=" + touchY + " touchId=" + touchId);
        reportAction(action, touchX, touchY);
        injector.down(touchX, touchY, touchId);
        injector.up(touchId);
        scheduleTouchIdRelease(touchId);
    }

    private int logicPathXAtProgress(LogicGesture gesture, double progress) {
        int elapsedMs = (int) Math.round(progress * gesture.durationSec * 1000.0);
        LogicPoint previous = gesture.points.get(0);
        for (int index = 1; index < gesture.points.size(); index++) {
            LogicPoint next = gesture.points.get(index);
            if (elapsedMs <= next.timeMs) {
                int spanMs = Math.max(1, next.timeMs - previous.timeMs);
                double local = Math.max(0.0, Math.min(1.0,
                        (elapsedMs - previous.timeMs) / (double) spanMs));
                double xRatio = previous.xRatio + (next.xRatio - previous.xRatio) * local;
                return toDisplayX(xRatio * Math.max(1, currentFrameW));
            }
            previous = next;
        }
        return gesture.endX;
    }

    private boolean sameLogicEvents(List<LogicEvent> events) {
        int size = events == null ? 0 : events.size();
        if (logicEvents.size() != size) {
            return false;
        }
        for (int index = 0; index < size; index++) {
            LogicEvent existing = logicEvents.get(index);
            LogicEvent candidate = events.get(index);
            if (existing.timeMs != candidate.timeMs
                    || existing.durationMs != candidate.durationMs
                    || !existing.type.equals(candidate.type)
                    || Double.compare(existing.xRatio, candidate.xRatio) != 0
                    || Double.compare(existing.endXRatio, candidate.endXRatio) != 0
                    || !sameLogicPoints(existing.points, candidate.points)) {
                return false;
            }
        }
        return true;
    }

    private boolean sameLogicPoints(List<LogicPoint> left, List<LogicPoint> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            LogicPoint leftPoint = left.get(index);
            LogicPoint rightPoint = right.get(index);
            if (leftPoint.timeMs != rightPoint.timeMs
                    || Double.compare(leftPoint.xRatio, rightPoint.xRatio) != 0) {
                return false;
            }
        }
        return true;
    }

    private void updateFlickHints(List<NoteTrack> confirmedTracks, double actionY) {
        List<Integer> expired = new ArrayList<>();
        for (Map.Entry<Integer, FlickHint> entry : flickHints.entrySet()) {
            if (currentTimestampSec - entry.getValue().timestampSec > Config.FLICK_HINT_SECONDS) {
                expired.add(entry.getKey());
            }
        }
        for (int key : expired) {
            flickHints.remove(key);
        }

        for (NoteTrack trk : confirmedTracks) {
            if (trk.cls != Detection.CLS_FLICK
                    || trk.y < actionY - s(Config.FLICK_BASE_LATE_Y)
                    || trk.y > actionY + s(Config.FLICK_LATE_TRIGGER_PX)) {
                continue;
            }
            double xAtLine = clickXAtLine(trk, actionY);
            int key = tracker.getKeyAt(xAtLine, actionY);
            if (key < 0) {
                continue;
            }
            flickHints.put(key, new FlickHint(key, xAtLine, currentTimestampSec));
        }
    }

    private FlickHint consumeFlickHint(NoteTrack trk, double actionY) {
        if (trk.cls == Detection.CLS_HOLD) {
            return null;
        }
        double xAtLine = clickXAtLine(trk, actionY);
        int key = tracker.getKeyAt(xAtLine, actionY);
        if (key < 0) {
            return null;
        }
        FlickHint hint = flickHints.get(key);
        if (hint == null) {
            return null;
        }
        if (currentTimestampSec - hint.timestampSec > Config.FLICK_HINT_SECONDS) {
            flickHints.remove(key);
            return null;
        }
        if (Math.abs(hint.xAtLine - xAtLine) > s(Config.FLICK_HINT_X_MARGIN)) {
            return null;
        }
        flickHints.remove(key);
        return hint;
    }

    private void clearFlickHint(NoteTrack trk, double actionY) {
        double xAtLine = clickXAtLine(trk, actionY);
        int key = tracker.getKeyAt(xAtLine, actionY);
        if (key >= 0) {
            flickHints.remove(key);
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
        releaseDueTouchIds();
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
            scheduleTouchIdRelease(ns.touchId);
            ns.touchId = -1;
        }
        ns.state = NoteState.STATE_FINISHED;
    }

    private void scheduleTouchIdRelease(int touchId) {
        if (touchId < 0 || availableTouchIds.contains(touchId)) {
            return;
        }
        double releaseAt = currentTimestampSec + Config.TOUCH_ID_RELEASE_DELAY_SECONDS;
        for (DelayedTouchRelease release : delayedTouchReleases) {
            if (release.touchId == touchId) {
                release.releaseAtSec = Math.max(release.releaseAtSec, releaseAt);
                return;
            }
        }
        delayedTouchReleases.add(new DelayedTouchRelease(touchId, releaseAt));
    }

    private void releaseDueTouchIds() {
        List<DelayedTouchRelease> remaining = new ArrayList<>();
        for (DelayedTouchRelease release : delayedTouchReleases) {
            if (release.releaseAtSec <= currentTimestampSec) {
                releaseTouchId(release.touchId);
            } else {
                remaining.add(release);
            }
        }
        delayedTouchReleases.clear();
        delayedTouchReleases.addAll(remaining);
    }

    private void releaseAllActiveTouches() {
        flickHints.clear();
        delayedTouchReleases.clear();
        for (LogicGesture gesture : activeLogicGestures) {
            injector.up(gesture.touchId);
            scheduleTouchIdRelease(gesture.touchId);
        }
        activeLogicGestures.clear();
        for (NoteState ns : noteStates.values()) {
            if (ns.touchId >= 0) {
                Log.i(TAG, "no click mode release touchId=" + ns.touchId);
                injector.up(ns.touchId);
                scheduleTouchIdRelease(ns.touchId);
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

    public static final class LogicEvent {
        public static final String TYPE_TAP = "tap";
        public static final String TYPE_HOLD = "hold";
        public static final String TYPE_SWIPE = "swipe";
        public static final String TYPE_FLICK = "flick";

        public final int timeMs;
        public final String type;
        public final double xRatio;
        public final double endXRatio;
        public final int durationMs;
        public final List<LogicPoint> points;

        public LogicEvent(
                int timeMs,
                String type,
                double xRatio,
                double endXRatio,
                int durationMs) {
            this(timeMs, type, xRatio, endXRatio, durationMs, null);
        }

        public LogicEvent(
                int timeMs,
                String type,
                double xRatio,
                double endXRatio,
                int durationMs,
                List<LogicPoint> points) {
            this.timeMs = timeMs;
            this.type = TYPE_HOLD.equals(type) || TYPE_SWIPE.equals(type) || TYPE_FLICK.equals(type)
                    ? type
                    : TYPE_TAP;
            this.xRatio = Math.max(0.05, Math.min(0.95, xRatio));
            this.endXRatio = Math.max(0.05, Math.min(0.95, endXRatio));
            this.durationMs = Math.max(0, durationMs);
            this.points = points == null || points.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(points));
        }
    }

    public static final class LogicPoint {
        public final int timeMs;
        public final double xRatio;

        public LogicPoint(int timeMs, double xRatio) {
            this.timeMs = Math.max(0, timeMs);
            this.xRatio = Math.max(0.05, Math.min(0.95, xRatio));
        }
    }

    private static final class LogicGesture {
        final int touchId;
        final String type;
        final int startX;
        final int endX;
        final int y;
        final int endY;
        final List<LogicPoint> points;
        final double startedAtSec;
        final double durationSec;

        LogicGesture(
                int touchId,
                String type,
                int startX,
                int endX,
                int y,
                int endY,
                List<LogicPoint> points,
                double startedAtSec,
                double durationSec) {
            this.touchId = touchId;
            this.type = type;
            this.startX = startX;
            this.endX = endX;
            this.y = y;
            this.endY = endY;
            this.points = points == null || points.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(points));
            this.startedAtSec = startedAtSec;
            this.durationSec = durationSec;
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

    private static final class FlickHint {
        final int key;
        final double xAtLine;
        final double timestampSec;

        FlickHint(int key, double xAtLine, double timestampSec) {
            this.key = key;
            this.xAtLine = xAtLine;
            this.timestampSec = timestampSec;
        }
    }

    private static final class DelayedTouchRelease {
        final int touchId;
        double releaseAtSec;

        DelayedTouchRelease(int touchId, double releaseAtSec) {
            this.touchId = touchId;
            this.releaseAtSec = releaseAtSec;
        }
    }
}
