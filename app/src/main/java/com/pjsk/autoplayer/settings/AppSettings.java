package com.pjsk.autoplayer.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.pjsk.autoplayer.core.Config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class AppSettings {
    private static final String PREFS_NAME = "pjsk_settings";
    private static final String KEY_PREVIEW_ENABLED = "preview_enabled";
    private static final String KEY_NO_CLICK_MODE = "no_click_mode";
    private static final String KEY_AUTO_SOLO_MODE_ENABLED = "auto_solo_mode_enabled";
    private static final String KEY_LOGIC_PLAY_MODE_ENABLED = "logic_play_mode_enabled";
    private static final String KEY_LOGIC_PROFILES_JSON = "logic_profiles_json";
    private static final String KEY_SELECTED_LOGIC_PROFILE_ID = "selected_logic_profile_id";
    private static final String KEY_DEBUG_DISPLAY_ENABLED = "debug_display_enabled";
    private static final String KEY_ACTION_Y = "action_y";
    private static final String KEY_TOUCH_MAPPING_MODE = "touch_mapping_mode";
    private static final String KEY_NOTE_MODEL_MODE = "note_model_mode";
    private static final String KEY_CAPTURE_TARGET_PACKAGE = "capture_target_package";
    private static final String KEY_CAPTURE_TARGET_LABEL = "capture_target_label";

    public static final int ACTION_Y_MIN = (int) Config.ACTION_Y_MIN;
    public static final int ACTION_Y_MAX = (int) Config.ACTION_Y_MAX;
    public static final int ACTION_Y_DEFAULT = (int) Config.ACTION_Y_DEFAULT;
    public static final int TOUCH_MAPPING_LANDSCAPE_90 = 0;
    public static final int TOUCH_MAPPING_DIRECT = 1;
    public static final int TOUCH_MAPPING_LANDSCAPE_270 = 2;
    public static final int NOTE_MODEL_ORIGINAL = 0;
    public static final int NOTE_MODEL_RETRAINED = 1;
    public static final int NOTE_MODEL_INT8 = 2;

    private static final String LEGACY_DEFAULT_LOGIC_PROFILE_ID = "default_2hz_center";
    private static final int DEFAULT_LOGIC_TAP_INTERVAL_MS = 500;
    private static final double DEFAULT_LOGIC_TAP_X_RATIO = 0.5;

    private AppSettings() {
    }

    public static boolean isPreviewEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PREVIEW_ENABLED, false);
    }

    public static void setPreviewEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PREVIEW_ENABLED, enabled).apply();
    }

    public static boolean isNoClickMode(Context context) {
        return prefs(context).getBoolean(KEY_NO_CLICK_MODE, false);
    }

    public static void setNoClickMode(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_NO_CLICK_MODE, enabled).apply();
    }

    public static boolean isAutoSoloModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_SOLO_MODE_ENABLED, true);
    }

    public static void setAutoSoloModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_SOLO_MODE_ENABLED, enabled).apply();
    }

    public static boolean isLogicPlayModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LOGIC_PLAY_MODE_ENABLED, false);
    }

    public static void setLogicPlayModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_LOGIC_PLAY_MODE_ENABLED, enabled).apply();
    }

    /** The app chosen by the user as the intended single-app capture target. */
    public static String captureTargetPackage(Context context) {
        return prefs(context).getString(KEY_CAPTURE_TARGET_PACKAGE, "");
    }

    public static String captureTargetLabel(Context context) {
        return prefs(context).getString(KEY_CAPTURE_TARGET_LABEL, "");
    }

    public static void setCaptureTarget(Context context, String packageName, String label) {
        prefs(context).edit()
                .putString(KEY_CAPTURE_TARGET_PACKAGE, packageName == null ? "" : packageName)
                .putString(KEY_CAPTURE_TARGET_LABEL, label == null ? "" : label)
                .apply();
    }

    public static String logicProfileLabel(Context context) {
        return selectedLogicProfile(context).name;
    }

    /** Fixed app-specific folder for logic JSON files. No storage permission is needed. */
    public static File logicProfilesDirectory(Context context) {
        Context appContext = context.getApplicationContext();
        File root = appContext.getExternalFilesDir(null);
        if (root == null) {
            root = appContext.getFilesDir();
        }
        File directory = new File(root, "logic_json");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    /**
     * Imports every .json file from the fixed logic directory. Profiles with
     * matching IDs replace their existing version, while all other profiles
     * remain available in the selector.
     */
    public static int importLogicProfilesFromDirectory(Context context) {
        File directory = logicProfilesDirectory(context);
        File[] files = directory.listFiles((dir, name) ->
                name != null && name.toLowerCase().endsWith(".json"));
        if (files == null || files.length == 0) {
            return 0;
        }
        List<File> sortedFiles = new ArrayList<>();
        Collections.addAll(sortedFiles, files);
        Collections.sort(sortedFiles, (left, right) ->
                left.getName().compareToIgnoreCase(right.getName()));

        List<LogicProfile> merged = parseLogicProfiles(
                prefs(context).getString(KEY_LOGIC_PROFILES_JSON, ""), false);
        int loadedCount = 0;
        for (File file : sortedFiles) {
            String json = readLogicJsonFile(file);
            if (json == null) {
                continue;
            }
            for (LogicProfile profile : parseLogicProfiles(json, false)) {
                replaceProfile(merged, profile);
                loadedCount++;
            }
        }
        if (loadedCount == 0) {
            return 0;
        }

        JSONArray array = new JSONArray();
        for (LogicProfile profile : merged) {
            array.put(profile.toJson());
        }
        String selectedId = selectedLogicProfileId(context);
        if (!containsProfile(merged, selectedId)) {
            selectedId = merged.isEmpty() ? "" : merged.get(0).id;
        }
        prefs(context).edit()
                .putString(KEY_LOGIC_PROFILES_JSON, array.toString())
                .putString(KEY_SELECTED_LOGIC_PROFILE_ID, selectedId)
                .apply();
        return loadedCount;
    }

    public static List<LogicProfileChoice> logicProfileChoices(Context context) {
        List<LogicProfileChoice> choices = new ArrayList<>();
        for (LogicProfile profile : logicProfiles(context)) {
            choices.add(new LogicProfileChoice(
                    profile.id,
                    profile.name,
                    profile.name + "  [" + profile.id + "]"));
        }
        return choices;
    }

    public static LogicProfileChoice selectedLogicProfileChoice(Context context) {
        LogicProfile selected = selectedLogicProfile(context);
        return new LogicProfileChoice(
                selected.id,
                selected.name,
                selected.name + "  [" + selected.id + "]");
    }

    public static boolean selectLogicProfile(Context context, String id) {
        if (id == null || !containsProfile(logicProfiles(context), id)) {
            return false;
        }
        prefs(context).edit().putString(KEY_SELECTED_LOGIC_PROFILE_ID, id).apply();
        return true;
    }

    public static int getLogicTapIntervalMs(Context context) {
        return selectedLogicProfile(context).tapIntervalMs;
    }

    public static double getLogicTapXRatio(Context context) {
        return selectedLogicProfile(context).tapXRatio;
    }

    /** Returns the selected profile as an execution-ready plan. */
    public static LogicPlayPlan getLogicPlayPlan(Context context) {
        LogicProfile profile = selectedLogicProfile(context);
        return new LogicPlayPlan(!profile.id.isEmpty(),
                profile.tapIntervalMs, profile.tapXRatio, profile.events);
    }

    public static String nextLogicProfile(Context context) {
        List<LogicProfile> profiles = logicProfiles(context);
        if (profiles.isEmpty()) {
            return "未找到逻辑 JSON";
        }
        String selectedId = selectedLogicProfileId(context);
        int selectedIndex = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(selectedId)) {
                selectedIndex = i;
                break;
            }
        }
        LogicProfile next = profiles.get((selectedIndex + 1) % profiles.size());
        prefs(context).edit().putString(KEY_SELECTED_LOGIC_PROFILE_ID, next.id).apply();
        return next.name;
    }

    public static String exportLogicProfilesJson(Context context) {
        JSONArray array = new JSONArray();
        for (LogicProfile profile : logicProfiles(context)) {
            array.put(profile.toJson());
        }
        return array.toString();
    }

    public static boolean importLogicProfilesJson(Context context, String json) {
        List<LogicProfile> imported = parseLogicProfiles(json, false);
        if (imported.isEmpty()) {
            return false;
        }
        JSONArray array = new JSONArray();
        for (LogicProfile profile : imported) {
            array.put(profile.toJson());
        }
        String selectedId = selectedLogicProfileId(context);
        if (!containsProfile(imported, selectedId)) {
            selectedId = imported.get(0).id;
        }
        prefs(context).edit()
                .putString(KEY_LOGIC_PROFILES_JSON, array.toString())
                .putString(KEY_SELECTED_LOGIC_PROFILE_ID, selectedId)
                .apply();
        return true;
    }

    public static boolean isDebugDisplayEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DEBUG_DISPLAY_ENABLED, false);
    }

    public static void setDebugDisplayEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DEBUG_DISPLAY_ENABLED, enabled).apply();
    }

    public static double getActionY(Context context) {
        return clampActionY(prefs(context).getFloat(KEY_ACTION_Y, (float) Config.ACTION_Y_DEFAULT));
    }

    public static void setActionY(Context context, double value) {
        prefs(context).edit().putFloat(KEY_ACTION_Y, (float) clampActionY(value)).apply();
    }

    public static void resetActionY(Context context) {
        setActionY(context, Config.ACTION_Y_DEFAULT);
    }

    public static double clampActionY(double value) {
        return Math.max(Config.ACTION_Y_MIN, Math.min(Config.ACTION_Y_MAX, value));
    }

    public static int getTouchMappingMode(Context context) {
        int mode = prefs(context).getInt(KEY_TOUCH_MAPPING_MODE, TOUCH_MAPPING_LANDSCAPE_90);
        if (mode < TOUCH_MAPPING_LANDSCAPE_90 || mode > TOUCH_MAPPING_LANDSCAPE_270) {
            return TOUCH_MAPPING_LANDSCAPE_90;
        }
        return mode;
    }

    public static void setTouchMappingMode(Context context, int mode) {
        prefs(context).edit()
                .putInt(KEY_TOUCH_MAPPING_MODE, clampTouchMappingMode(mode))
                .apply();
    }

    public static int nextTouchMappingMode(Context context) {
        int next = getTouchMappingMode(context) + 1;
        if (next > TOUCH_MAPPING_LANDSCAPE_270) {
            next = TOUCH_MAPPING_LANDSCAPE_90;
        }
        setTouchMappingMode(context, next);
        return next;
    }

    public static String touchMappingLabel(int mode) {
        switch (mode) {
            case TOUCH_MAPPING_DIRECT:
                return "直连";
            case TOUCH_MAPPING_LANDSCAPE_270:
                return "横屏270";
            case TOUCH_MAPPING_LANDSCAPE_90:
            default:
                return "横屏90";
        }
    }

    public static int getNoteModelMode(Context context) {
        int mode = prefs(context).getInt(KEY_NOTE_MODEL_MODE, NOTE_MODEL_ORIGINAL);
        if (mode < NOTE_MODEL_ORIGINAL || mode > NOTE_MODEL_INT8) {
            return NOTE_MODEL_ORIGINAL;
        }
        return mode;
    }

    public static void setNoteModelMode(Context context, int mode) {
        prefs(context).edit()
                .putInt(KEY_NOTE_MODEL_MODE, clampNoteModelMode(mode))
                .apply();
    }

    public static int nextNoteModelMode(Context context) {
        int next = getNoteModelMode(context) + 1;
        if (next > NOTE_MODEL_INT8) {
            next = NOTE_MODEL_ORIGINAL;
        }
        setNoteModelMode(context, next);
        return next;
    }

    public static String noteModelLabel(int mode) {
        switch (mode) {
            case NOTE_MODEL_RETRAINED:
                return "重训模型";
            case NOTE_MODEL_INT8:
                return "量化模型";
            case NOTE_MODEL_ORIGINAL:
            default:
                return "原版模型";
        }
    }

    private static int clampTouchMappingMode(int mode) {
        if (mode < TOUCH_MAPPING_LANDSCAPE_90 || mode > TOUCH_MAPPING_LANDSCAPE_270) {
            return TOUCH_MAPPING_LANDSCAPE_90;
        }
        return mode;
    }

    private static int clampNoteModelMode(int mode) {
        if (mode < NOTE_MODEL_ORIGINAL || mode > NOTE_MODEL_INT8) {
            return NOTE_MODEL_ORIGINAL;
        }
        return mode;
    }


    private static LogicProfile selectedLogicProfile(Context context) {
        List<LogicProfile> profiles = logicProfiles(context);
        String selectedId = selectedLogicProfileId(context);
        for (LogicProfile profile : profiles) {
            if (profile.id.equals(selectedId)) {
                return profile;
            }
        }
        if (profiles.isEmpty()) {
            return emptyLogicProfile();
        }
        LogicProfile fallback = profiles.get(0);
        prefs(context).edit().putString(KEY_SELECTED_LOGIC_PROFILE_ID, fallback.id).apply();
        return fallback;
    }

    private static String selectedLogicProfileId(Context context) {
        return prefs(context).getString(KEY_SELECTED_LOGIC_PROFILE_ID, "");
    }

    private static List<LogicProfile> logicProfiles(Context context) {
        return parseLogicProfiles(
                prefs(context).getString(KEY_LOGIC_PROFILES_JSON, ""), false);
    }

    private static List<LogicProfile> parseLogicProfiles(String json, boolean fallbackDefault) {
        List<LogicProfile> profiles = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return profiles;
        }
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("[")) {
                appendLogicProfiles(profiles, new JSONArray(trimmed));
            } else {
                JSONObject object = new JSONObject(trimmed);
                JSONArray array = object.optJSONArray("profiles");
                if (array != null) {
                    appendLogicProfiles(profiles, array);
                } else {
                    LogicProfile profile = LogicProfile.fromJson(object);
                    if (profile != null && !isLegacyDefaultProfile(profile)) {
                        profiles.add(profile);
                    }
                }
            }
        } catch (JSONException ignored) {
            profiles.clear();
        }
        return profiles;
    }

    private static void appendLogicProfiles(List<LogicProfile> profiles, JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            LogicProfile profile = LogicProfile.fromJson(array.optJSONObject(i));
            if (profile != null
                    && !isLegacyDefaultProfile(profile)
                    && !containsProfile(profiles, profile.id)) {
                profiles.add(profile);
            }
        }
    }

    private static void replaceProfile(List<LogicProfile> profiles, LogicProfile replacement) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(replacement.id)) {
                profiles.set(i, replacement);
                return;
            }
        }
        profiles.add(replacement);
    }

    private static String readLogicJsonFile(File file) {
        if (file == null || !file.isFile() || file.length() <= 0 || file.length() > 1_048_576) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean containsProfile(List<LogicProfile> profiles, String id) {
        for (LogicProfile profile : profiles) {
            if (profile.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLegacyDefaultProfile(LogicProfile profile) {
        return LEGACY_DEFAULT_LOGIC_PROFILE_ID.equals(profile.id);
    }

    private static LogicProfile emptyLogicProfile() {
        return new LogicProfile(
                "",
                "未选择逻辑",
                DEFAULT_LOGIC_TAP_INTERVAL_MS,
                DEFAULT_LOGIC_TAP_X_RATIO,
                Collections.emptyList());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** A display-safe entry for the main-screen logic selector. */
    public static final class LogicProfileChoice {
        public final String id;
        public final String name;
        public final String label;

        LogicProfileChoice(String id, String name, String label) {
            this.id = id;
            this.name = name;
            this.label = label;
        }
    }

    public static final class LogicPlayPlan {
        public final boolean valid;
        public final int tapIntervalMs;
        public final double tapXRatio;
        public final List<LogicEvent> events;

        LogicPlayPlan(boolean valid, int tapIntervalMs, double tapXRatio, List<LogicEvent> events) {
            this.valid = valid;
            this.tapIntervalMs = tapIntervalMs;
            this.tapXRatio = tapXRatio;
            this.events = events == null || events.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(events));
        }

        public boolean hasTimeline() {
            return !events.isEmpty();
        }
    }

    /** One timeline action. Coordinates are ratios of the captured game frame width. */
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

        LogicEvent(
                int timeMs,
                String type,
                double xRatio,
                double endXRatio,
                int durationMs,
                List<LogicPoint> points) {
            this.timeMs = timeMs;
            this.type = type;
            this.xRatio = xRatio;
            this.endXRatio = endXRatio;
            this.durationMs = durationMs;
            this.points = points == null || points.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(points));
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("timeMs", timeMs);
                object.put("type", type);
                object.put("xRatio", xRatio);
                if (TYPE_SWIPE.equals(type)) {
                    object.put("endXRatio", endXRatio);
                    if (!points.isEmpty()) {
                        JSONArray pointArray = new JSONArray();
                        for (LogicPoint point : points) {
                            pointArray.put(point.toJson());
                        }
                        object.put("points", pointArray);
                    }
                }
                if (!TYPE_TAP.equals(type)) {
                    object.put("durationMs", durationMs);
                }
            } catch (JSONException ignored) {
            }
            return object;
        }

        static LogicEvent fromJson(JSONObject object, double defaultXRatio) {
            if (object == null) {
                return null;
            }
            int timeMs = Math.max(0, Math.min(1_800_000,
                    object.has("timeMs") ? object.optInt("timeMs", 0) : object.optInt("t", 0)));
            String type = object.optString("type", object.optString("action", TYPE_TAP))
                    .trim().toLowerCase();
            if (!TYPE_HOLD.equals(type) && !TYPE_SWIPE.equals(type) && !TYPE_FLICK.equals(type)) {
                type = TYPE_TAP;
            }
            double xRatio = clampRatio(object.optDouble("xRatio", defaultXRatio));
            double endXRatio = clampRatio(object.optDouble("endXRatio", xRatio));
            int durationMs = object.optInt("durationMs", object.optInt("duration", 0));
            if (TYPE_TAP.equals(type)) {
                durationMs = 0;
            } else if (TYPE_HOLD.equals(type)) {
                durationMs = Math.max(10, Math.min(60_000, durationMs));
            } else if (TYPE_FLICK.equals(type)) {
                durationMs = Math.max(20, Math.min(1_000, durationMs == 0 ? 80 : durationMs));
            } else {
                durationMs = Math.max(20, Math.min(20_000, durationMs));
            }
            List<LogicPoint> points = TYPE_SWIPE.equals(type)
                    ? parsePoints(object.optJSONArray("points"), xRatio, endXRatio, durationMs)
                    : Collections.emptyList();
            return new LogicEvent(timeMs, type, xRatio, endXRatio, durationMs, points);
        }

        private static List<LogicPoint> parsePoints(
                JSONArray pointArray,
                double xRatio,
                double endXRatio,
                int durationMs) {
            if (pointArray == null || pointArray.length() == 0) {
                return Collections.emptyList();
            }
            List<LogicPoint> points = new ArrayList<>();
            for (int i = 0; i < pointArray.length(); i++) {
                LogicPoint point = LogicPoint.fromJson(pointArray.optJSONObject(i));
                if (point != null) {
                    points.add(point);
                }
            }
            if (points.isEmpty()) {
                return Collections.emptyList();
            }
            Collections.sort(points, new Comparator<LogicPoint>() {
                @Override
                public int compare(LogicPoint left, LogicPoint right) {
                    return Integer.compare(left.timeMs, right.timeMs);
                }
            });
            if (points.get(0).timeMs > 0) {
                points.add(0, new LogicPoint(0, xRatio));
            }
            if (points.get(points.size() - 1).timeMs < durationMs) {
                points.add(new LogicPoint(durationMs, endXRatio));
            }
            return points;
        }

        private static double clampRatio(double value) {
            return Math.max(0.05, Math.min(0.95, value));
        }
    }

    public static final class LogicPoint {
        public final int timeMs;
        public final double xRatio;

        LogicPoint(int timeMs, double xRatio) {
            this.timeMs = timeMs;
            this.xRatio = xRatio;
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("timeMs", timeMs);
                object.put("xRatio", xRatio);
            } catch (JSONException ignored) {
            }
            return object;
        }

        static LogicPoint fromJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            int timeMs = Math.max(0, Math.min(60_000,
                    object.has("timeMs") ? object.optInt("timeMs", 0) : object.optInt("t", 0)));
            double xRatio = LogicEvent.clampRatio(object.optDouble("xRatio", object.optDouble("x", 0.5)));
            return new LogicPoint(timeMs, xRatio);
        }
    }

    private static final class LogicProfile {
        final String id;
        final String name;
        final int tapIntervalMs;
        final double tapXRatio;
        final List<LogicEvent> events;

        LogicProfile(
                String id,
                String name,
                int tapIntervalMs,
                double tapXRatio,
                List<LogicEvent> events) {
            this.id = id;
            this.name = name;
            this.tapIntervalMs = tapIntervalMs;
            this.tapXRatio = tapXRatio;
            this.events = events == null || events.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(events));
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("name", name);
                object.put("tapIntervalMs", tapIntervalMs);
                object.put("tapXRatio", tapXRatio);
                if (!events.isEmpty()) {
                    JSONArray eventArray = new JSONArray();
                    for (LogicEvent event : events) {
                        eventArray.put(event.toJson());
                    }
                    object.put("events", eventArray);
                }
            } catch (JSONException ignored) {
            }
            return object;
        }

        static LogicProfile fromJson(JSONObject object) {
            if (object == null) {
                return null;
            }
            String id = object.optString("id", "").trim();
            String name = object.optString("name", "").trim();
            int tapIntervalMs = object.optInt("tapIntervalMs", DEFAULT_LOGIC_TAP_INTERVAL_MS);
            double tapXRatio = object.optDouble("tapXRatio", DEFAULT_LOGIC_TAP_X_RATIO);
            if (id.isEmpty() || name.isEmpty()) {
                return null;
            }
            tapIntervalMs = Math.max(80, Math.min(5000, tapIntervalMs));
            tapXRatio = Math.max(0.05, Math.min(0.95, tapXRatio));
            List<LogicEvent> events = parseEvents(object.optJSONArray("events"), tapXRatio);
            return new LogicProfile(id, name, tapIntervalMs, tapXRatio, events);
        }

        private static List<LogicEvent> parseEvents(JSONArray eventArray, double defaultXRatio) {
            if (eventArray == null || eventArray.length() == 0) {
                return Collections.emptyList();
            }
            List<LogicEvent> events = new ArrayList<>();
            for (int i = 0; i < eventArray.length(); i++) {
                LogicEvent event = LogicEvent.fromJson(eventArray.optJSONObject(i), defaultXRatio);
                if (event != null) {
                    events.add(event);
                }
            }
            Collections.sort(events, new Comparator<LogicEvent>() {
                @Override
                public int compare(LogicEvent left, LogicEvent right) {
                    return Integer.compare(left.timeMs, right.timeMs);
                }
            });
            return events;
        }
    }

}
