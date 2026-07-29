package com.pjsk.autoplayer.settings;

import android.util.Log;

import java.io.IOException;

public final class DebugDisplayController {
    private static final String TAG = "PJSK-DebugDisplay";

    private DebugDisplayController() {
    }

    public static boolean setEnabled(boolean enabled) {
        String value = enabled ? "1" : "0";
        String command = "settings put system show_touches " + value
                + "; settings put system pointer_location " + value;
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                Log.w(TAG, "debug display command exited code=" + exitCode);
                return false;
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "failed to update debug display settings", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
