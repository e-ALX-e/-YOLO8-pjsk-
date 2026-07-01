package com.pjsk.autoplayer.ncnn;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import com.pjsk.autoplayer.core.Config;
import com.pjsk.autoplayer.core.Detection;

import java.util.ArrayList;
import java.util.List;

public final class NcnnDetector {
    private static final String TAG = "PJSK-NCNN";
    private static final String NOTE_PARAM_PATH = "model_ncnn_model/model.ncnn.param";
    private static final String NOTE_BIN_PATH = "model_ncnn_model/model.ncnn.bin";

    private boolean nativeAvailable;
    private String status = "native library not loaded";
    private long nativeHandle;
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
        this(
                context,
                NOTE_PARAM_PATH,
                NOTE_BIN_PATH,
                4,
                Config.MODEL_CONFIDENCE,
                Config.NMS_IOU,
                Config.MODEL_IMAGE_SIZE);
    }

    public NcnnDetector(
            Context context,
            String paramPath,
            String binPath,
            int classCount,
            float confidence,
            float iou,
            int inputSize) {
        if (!libraryLoaded) {
            nativeAvailable = false;
            status = "native library not loaded; detector stub active";
            return;
        }
        try {
            AssetManager assets = context.getAssets();
            nativeHandle = nativeCreate(
                    assets,
                    paramPath,
                    binPath,
                    classCount,
                    confidence,
                    iou,
                    inputSize);
            nativeAvailable = nativeHandle != 0L;
            status = nativeStatus(nativeHandle);
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
        float[] raw = nativeDetect(nativeHandle, bitmap);
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

    public void close() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle);
            nativeHandle = 0L;
        }
        nativeAvailable = false;
    }

    private native long nativeCreate(
            AssetManager assetManager,
            String paramPath,
            String binPath,
            int classCount,
            float confidence,
            float iou,
            int inputSize);

    private native void nativeRelease(long handle);

    private native float[] nativeDetect(long handle, Bitmap bitmap);

    private native String nativeStatus(long handle);
}
