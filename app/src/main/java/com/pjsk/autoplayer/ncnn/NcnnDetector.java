package com.pjsk.autoplayer.ncnn;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import com.pjsk.autoplayer.core.Config;
import com.pjsk.autoplayer.core.Detection;
import com.pjsk.autoplayer.settings.AppSettings;

import java.util.ArrayList;
import java.util.List;

public final class NcnnDetector {
    private static final String TAG = "PJSK-NCNN";

    private boolean nativeAvailable;
    private String status = "native library not loaded";
    private static boolean libraryLoaded;

    static {
        try {
            System.loadLibrary("pjsk_native");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError ignored) {
            libraryLoaded = false;
        }
    }

    public NcnnDetector(Context context) {
        if (!libraryLoaded) {
            nativeAvailable = false;
            status = "native library not loaded; detector stub active";
            return;
        }
        try {
            AssetManager assets = context.getAssets();
            nativeAvailable = nativeInit(
                    assets,
                    AppSettings.getModelConfidence(context),
                    Config.NMS_IOU,
                    AppSettings.getModelImageSize(context));
            status = nativeStatus();
        } catch (Throwable t) {
            nativeAvailable = false;
            status = "NCNN unavailable: " + t.getMessage();
            Log.w(TAG, status, t);
        }
    }

    public List<Detection> detect(Bitmap bitmap) {
        List<Detection> detections = new ArrayList<>();
        if (!nativeAvailable || bitmap == null || bitmap.isRecycled()) {
            return detections;
        }
        float[] raw = nativeDetect(bitmap);
        if (raw == null) {
            return detections;
        }
        for (int i = 0; i + 5 < raw.length; i += 6) {
            detections.add(new Detection(
                    raw[i],
                    raw[i + 1],
                    raw[i + 2],
                    raw[i + 3],
                    (int) raw[i + 4],
                    raw[i + 5]));
        }
        return detections;
    }

    public String status() {
        return status;
    }

    private native boolean nativeInit(
            AssetManager assetManager,
            float confidence,
            float iou,
            int inputSize);

    private native float[] nativeDetect(Bitmap bitmap);

    private native String nativeStatus();
}
