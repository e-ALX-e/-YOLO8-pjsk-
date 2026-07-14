package com.pjsk.autoplayer.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.pjsk.autoplayer.core.Config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
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

    public static final int ACTION_Y_MIN = (int) Config.ACTION_Y_MIN;
    public static final int ACTION_Y_MAX = (int) Config.ACTION_Y_MAX;
    public static final int ACTION_Y_DEFAULT = (int) Config.ACTION_Y_DEFAULT;
    public static final int TOUCH_MAPPING_LANDSCAPE_90 = 0;
    public static final int TOUCH_MAPPING_DIRECT = 1;
    public static final int TOUCH_MAPPING_LANDSCAPE_270 = 2;
    public static final int NOTE_MODEL_ORIGINAL = 0;
    public static final int NOTE_MODEL_RETRAINED = 1;
    public static final int NOTE_MODEL_INT8 = 2;

    private static final String DEFAULT_LOGIC_PROFILE_ID = "default_2hz_center";
    private static final String DEFAULT_LOGIC_PROFILE_NAME = "\u5224\u5b9a\u7ebf\u4e2d\u5fc3 2 \u6b21/\u79d2";
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

    public static String logicProfileLabel(Context context) {
        return selectedLogicProfile(context).name;
    }

    public static int getLogicTapIntervalMs(Context context) {
        return selectedLogicProfile(context).tapIntervalMs;
    }

    public static double getLogicTapXRatio(Context context) {
        return selectedLogicProfile(context).tapXRatio;
    }

    public static String nextLogicProfile(Context context) {
        List<LogicProfile> profiles = logicProfiles(context);
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
        LogicProfile fallback = profiles.get(0);
        prefs(context).edit().putString(KEY_SELECTED_LOGIC_PROFILE_ID, fallback.id).apply();
        return fallback;
    }

    private static String selectedLogicProfileId(Context context) {
        return prefs(context).getString(KEY_SELECTED_LOGIC_PROFILE_ID, DEFAULT_LOGIC_PROFILE_ID);
    }

    private static List<LogicProfile> logicProfiles(Context context) {
        List<LogicProfile> profiles = parseLogicProfiles(
                prefs(context).getString(KEY_LOGIC_PROFILES_JSON, ""),
                true);
        if (!containsProfile(profiles, DEFAULT_LOGIC_PROFILE_ID)) {
            profiles.add(0, defaultLogicProfile());
        }
        return profiles;
    }

    private static List<LogicProfile> parseLogicProfiles(String json, boolean fallbackDefault) {
        List<LogicProfile> profiles = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            if (fallbackDefault) {
                profiles.add(defaultLogicProfile());
            }
            return profiles;
        }
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                LogicProfile profile = LogicProfile.fromJson(item);
                if (profile != null && !containsProfile(profiles, profile.id)) {
                    profiles.add(profile);
                }
            }
        } catch (JSONException ignored) {
            profiles.clear();
        }
        if (profiles.isEmpty() && fallbackDefault) {
            profiles.add(defaultLogicProfile());
        }
        return profiles;
    }

    private static boolean containsProfile(List<LogicProfile> profiles, String id) {
        for (LogicProfile profile : profiles) {
            if (profile.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static LogicProfile defaultLogicProfile() {
        return new LogicProfile(
                DEFAULT_LOGIC_PROFILE_ID,
                DEFAULT_LOGIC_PROFILE_NAME,
                DEFAULT_LOGIC_TAP_INTERVAL_MS,
                DEFAULT_LOGIC_TAP_X_RATIO);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static final class LogicProfile {
        final String id;
        final String name;
        final int tapIntervalMs;
        final double tapXRatio;

        LogicProfile(String id, String name, int tapIntervalMs, double tapXRatio) {
            this.id = id;
            this.name = name;
            this.tapIntervalMs = tapIntervalMs;
            this.tapXRatio = tapXRatio;
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("name", name);
                object.put("tapIntervalMs", tapIntervalMs);
                object.put("tapXRatio", tapXRatio);
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
            return new LogicProfile(id, name, tapIntervalMs, tapXRatio);
        }
    }

}
