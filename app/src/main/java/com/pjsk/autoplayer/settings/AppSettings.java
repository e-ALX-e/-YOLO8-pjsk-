package com.pjsk.autoplayer.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.pjsk.autoplayer.core.Config;

public final class AppSettings {
    private static final String PREFS_NAME = "pjsk_settings";
    private static final String KEY_PREVIEW_ENABLED = "preview_enabled";
    private static final String KEY_NO_CLICK_MODE = "no_click_mode";
    private static final String KEY_DEBUG_DISPLAY_ENABLED = "debug_display_enabled";
    private static final String KEY_AUTO_CONTINUE_ENABLED = "auto_continue_enabled";
    private static final String KEY_AUTO_CONTINUE_DEFAULT_APPLIED = "auto_continue_default_applied";
    private static final String KEY_ACTION_Y = "action_y";
    private static final String KEY_TOUCH_MAPPING_MODE = "touch_mapping_mode";

    public static final int ACTION_Y_MIN = (int) Config.ACTION_Y_MIN;
    public static final int ACTION_Y_MAX = (int) Config.ACTION_Y_MAX;
    public static final int ACTION_Y_DEFAULT = (int) Config.ACTION_Y_DEFAULT;
    public static final int TOUCH_MAPPING_LANDSCAPE_90 = 0;
    public static final int TOUCH_MAPPING_DIRECT = 1;
    public static final int TOUCH_MAPPING_LANDSCAPE_270 = 2;

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

    public static boolean isDebugDisplayEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DEBUG_DISPLAY_ENABLED, false);
    }

    public static void setDebugDisplayEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DEBUG_DISPLAY_ENABLED, enabled).apply();
    }

    public static boolean isAutoContinueEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_CONTINUE_ENABLED, true);
    }

    public static void setAutoContinueEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_CONTINUE_ENABLED, enabled).apply();
    }

    public static void ensureAutoContinueDefaultEnabled(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean(KEY_AUTO_CONTINUE_DEFAULT_APPLIED, false)) {
            return;
        }
        preferences.edit()
                .putBoolean(KEY_AUTO_CONTINUE_ENABLED, true)
                .putBoolean(KEY_AUTO_CONTINUE_DEFAULT_APPLIED, true)
                .apply();
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

    private static int clampTouchMappingMode(int mode) {
        if (mode < TOUCH_MAPPING_LANDSCAPE_90 || mode > TOUCH_MAPPING_LANDSCAPE_270) {
            return TOUCH_MAPPING_LANDSCAPE_90;
        }
        return mode;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
