package com.pjsk.autoplayer.ncnn;

import android.content.Context;
import android.graphics.Bitmap;

import com.pjsk.autoplayer.core.Detection;

import java.util.List;

public final class UiButtonDetector {
    public static final int CLS_CONFIRM = 0;
    public static final int CLS_PLAY = 1;

    private static final String PARAM_PATH = "ui_button_ncnn_model/model.ncnn.param";
    private static final String BIN_PATH = "ui_button_ncnn_model/model.ncnn.bin";
    private static final float CONFIDENCE = 0.05f;
    private static final float IOU = 0.45f;
    private static final int INPUT_SIZE = 640;

    private final NcnnDetector detector;

    public UiButtonDetector(Context context) {
        detector = new NcnnDetector(
                context,
                PARAM_PATH,
                BIN_PATH,
                2,
                CONFIDENCE,
                IOU,
                INPUT_SIZE);
    }

    public Detection findBest(Bitmap frame, int targetClass) {
        return findBest(detect(frame), targetClass);
    }

    public List<Detection> detect(Bitmap frame) {
        return detector.detect(frame);
    }

    public Detection findBest(List<Detection> detections, int targetClass) {
        Detection best = null;
        for (Detection detection : detections) {
            if (detection.cls != targetClass) {
                continue;
            }
            if (best == null || detection.confidence > best.confidence) {
                best = detection;
            }
        }
        return best;
    }

    public String status() {
        return detector.status();
    }

    public void close() {
        detector.close();
    }
}
