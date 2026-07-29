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
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AppSettings {
    private static final String PREFS_NAME = "pjsk_settings";
    private static final String KEY_PREVIEW_ENABLED = "preview_enabled";
    private static final String KEY_NO_CLICK_MODE = "no_click_mode";
    private static final String KEY_AUTO_SOLO_MODE_ENABLED = "auto_solo_mode_enabled";
    private static final String KEY_LOGIC_PLAY_MODE_ENABLED = "logic_play_mode_enabled";
    private static final String KEY_LOGIC_PROFILES_JSON = "logic_profiles_json";
    private static final String KEY_SELECTED_LOGIC_PROFILE_ID = "selected_logic_profile_id";
    private static final String KEY_SELECTED_LOGIC_CHART_SONG_ID = "selected_logic_chart_song_id";
    private static final String KEY_SELECTED_LOGIC_CHART_DIFFICULTY = "selected_logic_chart_difficulty";
    private static final String KEY_DEBUG_DISPLAY_ENABLED = "debug_display_enabled";
    private static final String KEY_ACTION_Y = "action_y";
    private static final String KEY_ACTION_Y_CALIBRATION_LOCKED = "action_y_calibration_locked";
    private static final String KEY_TOUCH_MAPPING_MODE = "touch_mapping_mode";
    private static final String KEY_NOTE_MODEL_MODE = "note_model_mode";
    private static final String KEY_OVERLAY_CUSTOM_ACTION = "overlay_custom_action";
    private static final String KEY_OVERLAY_HIDDEN = "overlay_hidden";
    private static final String KEY_OVERLAY_COLLAPSED = "overlay_collapsed";
    private static final String KEY_OVERLAY_PARAMETERS_VISIBLE = "overlay_parameters_visible";
    private static final String KEY_CAPTURE_TARGET_PACKAGE = "capture_target_package";
    private static final String KEY_CAPTURE_TARGET_LABEL = "capture_target_label";
    private static final int MAX_LOGIC_JSON_BYTES = 8 * 1024 * 1024;
    private static final String LOGIC_CHART_INDEX_FILE = "logic_chart_index.json";

    public static final int ACTION_Y_MIN = (int) Config.ACTION_Y_MIN;
    public static final int ACTION_Y_MAX = (int) Config.ACTION_Y_MAX;
    public static final int ACTION_Y_DEFAULT = (int) Config.ACTION_Y_DEFAULT;
    public static final int TOUCH_MAPPING_LANDSCAPE_90 = 0;
    public static final int TOUCH_MAPPING_DIRECT = 1;
    public static final int TOUCH_MAPPING_LANDSCAPE_270 = 2;
    public static final int NOTE_MODEL_ORIGINAL = 0;
    public static final int NOTE_MODEL_RETRAINED = 1;
    public static final int NOTE_MODEL_INT8 = 2;
    public static final int OVERLAY_CUSTOM_NONE = 0;
    public static final int OVERLAY_CUSTOM_PREVIEW = 1;
    public static final int OVERLAY_CUSTOM_NO_CLICK = 2;
    public static final int OVERLAY_CUSTOM_AUTO_SOLO = 3;
    public static final int OVERLAY_CUSTOM_LOGIC_PLAY = 4;
    public static final int OVERLAY_CUSTOM_SWITCH_LOGIC = 5;
    public static final int OVERLAY_CUSTOM_RESET_STATE = 6;
    public static final int OVERLAY_CUSTOM_DEBUG_DISPLAY = 7;
    public static final int OVERLAY_CUSTOM_SCREEN_RECORD = 8;
    public static final int OVERLAY_CUSTOM_STOP = 9;

    private static final String LEGACY_DEFAULT_LOGIC_PROFILE_ID = "default_2hz_center";
    private static final int DEFAULT_LOGIC_TAP_INTERVAL_MS = 500;
    private static final double DEFAULT_LOGIC_TAP_X_RATIO = 0.5;
    private static boolean logicProfileSourcesLoaded;
    private static List<LogicProfileSource> cachedLogicProfileSources = Collections.emptyList();
    private static boolean logicChartIndexLoaded;
    private static List<LogicChartEntry> cachedLogicChartIndex = Collections.emptyList();
    private static String cachedLoadedProfileSourceId = "";
    private static long cachedLoadedProfileModified;
    private static long cachedLoadedProfileLength;
    private static LogicProfile cachedLoadedProfile;

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
        return selectedLogicProfileSource(context).name;
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

    /** Rebuilds the filename-only chart index used by the song and difficulty selectors. */
    public static synchronized int refreshLogicChartIndex(Context context) {
        File directory = logicProfilesDirectory(context);
        File[] files = directory.listFiles((dir, name) ->
                name != null && name.toLowerCase().endsWith(".json")
                        && !LOGIC_CHART_INDEX_FILE.equalsIgnoreCase(name));
        List<File> sortedFiles = new ArrayList<>();
        if (files != null) {
            Collections.addAll(sortedFiles, files);
        }
        Collections.sort(sortedFiles, (left, right) ->
                left.getName().compareToIgnoreCase(right.getName()));

        Map<String, List<String>> difficultiesBySong = new HashMap<>();
        List<LogicProfileSource> sources = new ArrayList<>();
        for (File file : sortedFiles) {
            String id = fileIdForName(file.getName());
            if (id.isEmpty()) {
                continue;
            }
            sources.add(new LogicProfileSource(id, displayNameForFileId(id), file));
            LogicChartFileName chart = LogicChartFileName.parse(id);
            if (chart == null) {
                continue;
            }
            List<String> difficulties = difficultiesBySong.get(chart.songId);
            if (difficulties == null) {
                difficulties = new ArrayList<>();
                difficultiesBySong.put(chart.songId, difficulties);
            }
            if (!difficulties.contains(chart.difficulty)) {
                difficulties.add(chart.difficulty);
            }
        }

        List<LogicChartEntry> charts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : difficultiesBySong.entrySet()) {
            List<String> difficulties = entry.getValue();
            Collections.sort(difficulties, AppSettings::compareDifficulties);
            charts.add(new LogicChartEntry(entry.getKey(), difficulties));
        }
        Collections.sort(charts, (left, right) -> compareSongIds(left.songId, right.songId));
        cachedLogicChartIndex = Collections.unmodifiableList(charts);
        logicChartIndexLoaded = true;
        cachedLogicProfileSources = Collections.unmodifiableList(sources);
        logicProfileSourcesLoaded = true;
        cachedLoadedProfile = null;
        cachedLoadedProfileSourceId = "";
        writeLogicChartIndex(directory, charts);
        ensureSelectedLogicChart(context, charts);
        return charts.size();
    }

    public static List<String> logicChartSongIds(Context context) {
        List<String> ids = new ArrayList<>();
        for (LogicChartEntry chart : logicChartIndex(context)) {
            ids.add(chart.songId);
        }
        return ids;
    }

    public static List<String> logicChartDifficulties(Context context, String songId) {
        LogicChartEntry chart = findLogicChart(logicChartIndex(context), songId);
        return chart == null ? Collections.emptyList() : chart.difficulties;
    }

    public static String selectedLogicChartSongId(Context context) {
        return prefs(context).getString(KEY_SELECTED_LOGIC_CHART_SONG_ID, "");
    }

    public static String selectedLogicChartDifficulty(Context context) {
        return prefs(context).getString(KEY_SELECTED_LOGIC_CHART_DIFFICULTY, "");
    }

    public static boolean selectLogicChart(Context context, String songId, String difficulty) {
        LogicChartEntry chart = findLogicChart(logicChartIndex(context), songId);
        if (chart == null || difficulty == null || !chart.difficulties.contains(difficulty)) {
            return false;
        }
        String profileId = chart.songId + "_" + difficulty;
        prefs(context).edit()
                .putString(KEY_SELECTED_LOGIC_CHART_SONG_ID, chart.songId)
                .putString(KEY_SELECTED_LOGIC_CHART_DIFFICULTY, difficulty)
                .putString(KEY_SELECTED_LOGIC_PROFILE_ID, profileId)
                .apply();
        return true;
    }

    /** Reapplies the chart selected by song ID and difficulty without cycling unrelated files. */
    public static String reloadSelectedLogicChart(Context context) {
        String songId = selectedLogicChartSongId(context);
        String difficulty = selectedLogicChartDifficulty(context);
        if (!selectLogicChart(context, songId, difficulty)) {
            return "\u672a\u627e\u5230\u5df2\u9009\u8c31\u9762";
        }
        return songId + " " + difficulty;
    }

    /** Refreshes the selector index without reading or parsing chart events. */
    public static int importLogicProfilesFromDirectory(Context context) {
        refreshLogicChartIndex(context);
        return logicProfileSources(context).size();
    }

    public static List<LogicProfileChoice> logicProfileChoices(Context context) {
        List<LogicProfileChoice> choices = new ArrayList<>();
        for (LogicProfileSource profile : logicProfileSources(context)) {
            choices.add(new LogicProfileChoice(
                    profile.id,
                    profile.name,
                    profile.name + "  [" + profile.id + "]"));
        }
        return choices;
    }

    public static LogicProfileChoice selectedLogicProfileChoice(Context context) {
        LogicProfileSource selected = selectedLogicProfileSource(context);
        return new LogicProfileChoice(
                selected.id,
                selected.name,
                selected.name + "  [" + selected.id + "]");
    }

    public static boolean selectLogicProfile(Context context, String id) {
        if (id == null || findLogicProfileSource(logicProfileSources(context), id) == null) {
            return false;
        }
        prefs(context).edit().putString(KEY_SELECTED_LOGIC_PROFILE_ID, id).apply();
        return true;
    }

    public static int getLogicTapIntervalMs(Context context) {
        return loadSelectedLogicProfile(context).tapIntervalMs;
    }

    public static double getLogicTapXRatio(Context context) {
        return loadSelectedLogicProfile(context).tapXRatio;
    }

    /** Returns the selected profile as an execution-ready plan. */
    public static LogicPlayPlan getLogicPlayPlan(Context context) {
        LogicProfile profile = loadSelectedLogicProfile(context);
        return new LogicPlayPlan(!profile.id.isEmpty(),
                profile.tapIntervalMs, profile.tapXRatio, profile.events);
    }

    public static String nextLogicProfile(Context context) {
        List<LogicProfileSource> profiles = logicProfileSources(context);
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
        LogicProfileSource next = profiles.get((selectedIndex + 1) % profiles.size());
        prefs(context).edit().putString(KEY_SELECTED_LOGIC_PROFILE_ID, next.id).apply();
        return next.name;
    }

    public static String exportLogicProfilesJson(Context context) {
        LogicProfile profile = loadSelectedLogicProfile(context);
        return profile.id.isEmpty() ? "[]" : profile.toJson().toString();
    }

    public static boolean importLogicProfilesJson(Context context, String json) {
        List<LogicProfile> imported = parseLogicProfiles(json, false);
        if (imported.isEmpty()) {
            return false;
        }
        File directory = logicProfilesDirectory(context);
        for (LogicProfile profile : imported) {
            if (!writeLogicProfileFile(directory, profile)) {
                return false;
            }
        }
        importLogicProfilesFromDirectory(context);
        selectLogicProfile(context, fileIdForProfile(imported.get(0)));
        return true;
    }

    public static boolean isDebugDisplayEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DEBUG_DISPLAY_ENABLED, false);
    }

    public static void setDebugDisplayEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DEBUG_DISPLAY_ENABLED, enabled).apply();
    }

    public static boolean isOverlayHidden(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY_HIDDEN, false);
    }

    public static void setOverlayHidden(Context context, boolean hidden) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_HIDDEN, hidden).apply();
    }

    public static boolean isOverlayCollapsed(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY_COLLAPSED, false);
    }

    public static void setOverlayCollapsed(Context context, boolean collapsed) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_COLLAPSED, collapsed).apply();
    }

    public static boolean isOverlayParametersVisible(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY_PARAMETERS_VISIBLE, false);
    }

    public static void setOverlayParametersVisible(Context context, boolean visible) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_PARAMETERS_VISIBLE, visible).apply();
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

    public static boolean isActionYCalibrationLocked(Context context) {
        return prefs(context).getBoolean(KEY_ACTION_Y_CALIBRATION_LOCKED, true);
    }

    public static void setActionYCalibrationLocked(Context context, boolean locked) {
        prefs(context).edit().putBoolean(KEY_ACTION_Y_CALIBRATION_LOCKED, locked).apply();
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

    public static int getOverlayCustomAction(Context context) {
        int action = prefs(context).getInt(KEY_OVERLAY_CUSTOM_ACTION, OVERLAY_CUSTOM_NONE);
        return action >= OVERLAY_CUSTOM_NONE && action <= OVERLAY_CUSTOM_STOP
                ? action : OVERLAY_CUSTOM_NONE;
    }

    public static void setOverlayCustomAction(Context context, int action) {
        prefs(context).edit()
                .putInt(KEY_OVERLAY_CUSTOM_ACTION, clampOverlayCustomAction(action))
                .apply();
    }

    public static int nextOverlayCustomAction(Context context) {
        int action = getOverlayCustomAction(context) + 1;
        if (action > OVERLAY_CUSTOM_STOP) {
            action = OVERLAY_CUSTOM_NONE;
        }
        setOverlayCustomAction(context, action);
        return action;
    }

    public static String overlayCustomActionLabel(int action) {
        switch (action) {
            case OVERLAY_CUSTOM_PREVIEW:
                return "\u9884\u89c8";
            case OVERLAY_CUSTOM_NO_CLICK:
                return "\u4e0d\u70b9\u51fb";
            case OVERLAY_CUSTOM_AUTO_SOLO:
                return "\u81ea\u52a8\u5355\u4eba";
            case OVERLAY_CUSTOM_LOGIC_PLAY:
                return "\u903b\u8f91\u6f14\u594f";
            case OVERLAY_CUSTOM_SWITCH_LOGIC:
                return "\u6362\u8c31";
            case OVERLAY_CUSTOM_RESET_STATE:
                return "\u91cd\u7f6e\u72b6\u6001";
            case OVERLAY_CUSTOM_DEBUG_DISPLAY:
                return "\u8c03\u8bd5\u663e\u793a";
            case OVERLAY_CUSTOM_SCREEN_RECORD:
                return "\u5f55\u5c4f";
            case OVERLAY_CUSTOM_STOP:
                return "\u505c\u6b62\u8fd0\u884c";
            case OVERLAY_CUSTOM_NONE:
            default:
                return "\u4e0d\u663e\u793a";
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

    private static int clampOverlayCustomAction(int action) {
        return action >= OVERLAY_CUSTOM_NONE && action <= OVERLAY_CUSTOM_STOP
                ? action : OVERLAY_CUSTOM_NONE;
    }


    /** Reads exactly one selected file and reuses it until that file changes. */
    private static synchronized LogicProfile loadSelectedLogicProfile(Context context) {
        LogicProfileSource source = selectedLogicProfileSource(context);
        if (source.file == null) {
            return emptyLogicProfile();
        }
        if (source.id.equals(cachedLoadedProfileSourceId)
                && source.file.lastModified() == cachedLoadedProfileModified
                && source.file.length() == cachedLoadedProfileLength
                && cachedLoadedProfile != null) {
            return cachedLoadedProfile;
        }
        String json = readLogicJsonFile(source.file);
        List<LogicProfile> profiles = json == null
                ? Collections.emptyList()
                : parseLogicProfiles(json, false);
        LogicProfile loaded = profiles.isEmpty() ? emptyLogicProfile() : profiles.get(0);
        cachedLoadedProfileSourceId = source.id;
        cachedLoadedProfileModified = source.file.lastModified();
        cachedLoadedProfileLength = source.file.length();
        cachedLoadedProfile = loaded;
        return loaded;
    }

    private static LogicProfileSource selectedLogicProfileSource(Context context) {
        String selectedId = selectedLogicProfileId(context);
        if (!selectedId.isEmpty()) {
            File directory = logicProfilesDirectory(context);
            File logicFile = new File(directory, selectedId + ".logic.json");
            File jsonFile = new File(directory, selectedId + ".json");
            File selectedFile = logicFile.isFile() ? logicFile : (jsonFile.isFile() ? jsonFile : null);
            if (selectedFile != null) {
                return new LogicProfileSource(
                        selectedId,
                        displayNameForFileId(selectedId),
                        selectedFile);
            }
        }
        List<LogicProfileSource> profiles = logicProfileSources(context);
        LogicProfileSource selected = findLogicProfileSource(profiles, selectedId);
        if (selected != null) {
            if (!selected.id.equals(selectedId)) {
                prefs(context).edit().putString(KEY_SELECTED_LOGIC_PROFILE_ID, selected.id).apply();
            }
            return selected;
        }
        if (profiles.isEmpty()) {
            return LogicProfileSource.empty();
        }
        LogicProfileSource fallback = profiles.get(0);
        prefs(context).edit().putString(KEY_SELECTED_LOGIC_PROFILE_ID, fallback.id).apply();
        return fallback;
    }

    private static String selectedLogicProfileId(Context context) {
        return prefs(context).getString(KEY_SELECTED_LOGIC_PROFILE_ID, "");
    }

    private static synchronized List<LogicProfileSource> refreshLogicProfileSources(Context context) {
        File[] files = logicProfilesDirectory(context).listFiles((dir, name) ->
                name != null && name.toLowerCase().endsWith(".json")
                        && !LOGIC_CHART_INDEX_FILE.equalsIgnoreCase(name));
        List<File> sortedFiles = new ArrayList<>();
        if (files != null) {
            Collections.addAll(sortedFiles, files);
        }
        Collections.sort(sortedFiles, (left, right) ->
                left.getName().compareToIgnoreCase(right.getName()));

        List<LogicProfileSource> sources = new ArrayList<>();
        for (File file : sortedFiles) {
            String id = fileIdForName(file.getName());
            if (!id.isEmpty()) {
                sources.add(new LogicProfileSource(id, displayNameForFileId(id), file));
            }
        }
        cachedLogicProfileSources = Collections.unmodifiableList(sources);
        logicProfileSourcesLoaded = true;
        cachedLoadedProfile = null;
        cachedLoadedProfileSourceId = "";
        return cachedLogicProfileSources;
    }

    private static synchronized List<LogicProfileSource> logicProfileSources(Context context) {
        return logicProfileSourcesLoaded
                ? cachedLogicProfileSources
                : refreshLogicProfileSources(context);
    }

    private static synchronized List<LogicChartEntry> logicChartIndex(Context context) {
        if (logicChartIndexLoaded) {
            return cachedLogicChartIndex;
        }
        File indexFile = new File(logicProfilesDirectory(context), LOGIC_CHART_INDEX_FILE);
        List<LogicChartEntry> charts = readLogicChartIndex(indexFile);
        if (charts.isEmpty()) {
            refreshLogicChartIndex(context);
            return cachedLogicChartIndex;
        }
        cachedLogicChartIndex = Collections.unmodifiableList(charts);
        logicChartIndexLoaded = true;
        ensureSelectedLogicChart(context, charts);
        return cachedLogicChartIndex;
    }

    private static List<LogicChartEntry> readLogicChartIndex(File indexFile) {
        String json = readLogicJsonFile(indexFile);
        if (json == null) {
            return Collections.emptyList();
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray chartArray = root.optJSONArray("charts");
            if (chartArray == null) {
                return Collections.emptyList();
            }
            List<LogicChartEntry> charts = new ArrayList<>();
            for (int i = 0; i < chartArray.length(); i++) {
                JSONObject chart = chartArray.optJSONObject(i);
                if (chart == null) {
                    continue;
                }
                String songId = chart.optString("songId", "").trim();
                JSONArray difficultyArray = chart.optJSONArray("difficulties");
                if (!isDigits(songId) || difficultyArray == null) {
                    continue;
                }
                List<String> difficulties = new ArrayList<>();
                for (int j = 0; j < difficultyArray.length(); j++) {
                    String difficulty = difficultyArray.optString(j, "").trim().toLowerCase();
                    if (!difficulty.isEmpty() && !difficulties.contains(difficulty)) {
                        difficulties.add(difficulty);
                    }
                }
                if (!difficulties.isEmpty()) {
                    Collections.sort(difficulties, AppSettings::compareDifficulties);
                    charts.add(new LogicChartEntry(songId, difficulties));
                }
            }
            Collections.sort(charts, (left, right) -> compareSongIds(left.songId, right.songId));
            return charts;
        } catch (JSONException ignored) {
            return Collections.emptyList();
        }
    }

    private static void writeLogicChartIndex(File directory, List<LogicChartEntry> charts) {
        JSONObject root = new JSONObject();
        JSONArray chartArray = new JSONArray();
        for (LogicChartEntry chart : charts) {
            JSONObject item = new JSONObject();
            try {
                item.put("songId", chart.songId);
                JSONArray difficulties = new JSONArray();
                for (String difficulty : chart.difficulties) {
                    difficulties.put(difficulty);
                }
                item.put("difficulties", difficulties);
                chartArray.put(item);
            } catch (JSONException ignored) {
            }
        }
        try {
            root.put("version", 1);
            root.put("charts", chartArray);
            try (FileOutputStream output = new FileOutputStream(
                    new File(directory, LOGIC_CHART_INDEX_FILE), false)) {
                output.write(root.toString().getBytes("UTF-8"));
                output.flush();
            }
        } catch (IOException | JSONException ignored) {
        }
    }

    private static void ensureSelectedLogicChart(Context context, List<LogicChartEntry> charts) {
        if (charts.isEmpty()) {
            return;
        }
        String songId = selectedLogicChartSongId(context);
        String difficulty = selectedLogicChartDifficulty(context);
        LogicChartEntry current = findLogicChart(charts, songId);
        if (current == null || !current.difficulties.contains(difficulty)) {
            LogicChartEntry fallback = charts.get(0);
            selectLogicChart(context, fallback.songId, fallback.difficulties.get(0));
        }
    }

    private static LogicChartEntry findLogicChart(List<LogicChartEntry> charts, String songId) {
        if (songId == null) {
            return null;
        }
        for (LogicChartEntry chart : charts) {
            if (chart.songId.equals(songId)) {
                return chart;
            }
        }
        return null;
    }

    private static int compareSongIds(String left, String right) {
        try {
            return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        } catch (NumberFormatException ignored) {
            return left.compareTo(right);
        }
    }

    private static int compareDifficulties(String left, String right) {
        int leftRank = difficultyRank(left);
        int rightRank = difficultyRank(right);
        return leftRank != rightRank ? Integer.compare(leftRank, rightRank) : left.compareTo(right);
    }

    private static int difficultyRank(String difficulty) {
        if ("easy".equals(difficulty)) return 0;
        if ("normal".equals(difficulty)) return 1;
        if ("hard".equals(difficulty)) return 2;
        if ("expert".equals(difficulty)) return 3;
        if ("master".equals(difficulty)) return 4;
        if ("append".equals(difficulty)) return 5;
        return 100;
    }

    private static boolean isDigits(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static LogicProfileSource findLogicProfileSource(
            List<LogicProfileSource> sources, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (LogicProfileSource source : sources) {
            if (source.id.equals(id) || source.matchesLegacyId(id)) {
                return source;
            }
        }
        return null;
    }

    private static String fileIdForProfile(LogicProfile profile) {
        return fileIdForName(safeFileName(profile.id) + ".logic.json");
    }

    private static String fileIdForName(String name) {
        if (name == null) {
            return "";
        }
        String id = name.trim();
        if (id.toLowerCase().endsWith(".logic.json")) {
            id = id.substring(0, id.length() - ".logic.json".length());
        } else if (id.toLowerCase().endsWith(".json")) {
            id = id.substring(0, id.length() - ".json".length());
        }
        return id.trim();
    }

    private static String displayNameForFileId(String id) {
        return id.isEmpty() ? "未选择逻辑" : id.replace('_', ' ');
    }

    private static String safeFileName(String value) {
        return value == null ? "logic" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static boolean writeLogicProfileFile(File directory, LogicProfile profile) {
        File target = new File(directory, safeFileName(profile.id) + ".logic.json");
        try (FileOutputStream output = new FileOutputStream(target, false)) {
            output.write(profile.toJson().toString().getBytes("UTF-8"));
            output.flush();
            return true;
        } catch (IOException ignored) {
            return false;
        }
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
        if (file == null || !file.isFile() || file.length() <= 0
                || file.length() > MAX_LOGIC_JSON_BYTES) {
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

    /** Metadata only. Event data stays in the JSON file until playback begins. */
    private static final class LogicProfileSource {
        final String id;
        final String name;
        final File file;

        LogicProfileSource(String id, String name, File file) {
            this.id = id;
            this.name = name;
            this.file = file;
        }

        static LogicProfileSource empty() {
            return new LogicProfileSource("", "未选择逻辑", null);
        }

        boolean matchesLegacyId(String legacyId) {
            return id.equals(legacyId)
                    || (file != null && file.getName().equalsIgnoreCase(legacyId + ".logic.json"));
        }
    }

    /** A single song entry in the filename-only selector index. */
    private static final class LogicChartEntry {
        final String songId;
        final List<String> difficulties;

        LogicChartEntry(String songId, List<String> difficulties) {
            this.songId = songId;
            this.difficulties = Collections.unmodifiableList(new ArrayList<>(difficulties));
        }
    }

    /** Parses a conventional chart filename such as 0001_easy.logic.json. */
    private static final class LogicChartFileName {
        final String songId;
        final String difficulty;

        LogicChartFileName(String songId, String difficulty) {
            this.songId = songId;
            this.difficulty = difficulty;
        }

        static LogicChartFileName parse(String fileId) {
            if (fileId == null) {
                return null;
            }
            int separator = fileId.indexOf('_');
            if (separator <= 0 || separator >= fileId.length() - 1) {
                return null;
            }
            String songId = fileId.substring(0, separator).trim();
            String difficulty = fileId.substring(separator + 1).trim().toLowerCase();
            if (!isDigits(songId) || difficulty.isEmpty()) {
                return null;
            }
            return new LogicChartFileName(songId, difficulty);
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
