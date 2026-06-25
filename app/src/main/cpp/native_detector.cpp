#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>

#ifdef PJSK_WITH_NCNN
#include "net.h"
#include "gpu.h"
#endif

namespace {

constexpr int kClassCount = 4;
constexpr const char* kParamPath = "model_ncnn_model/model.ncnn.param";
constexpr const char* kBinPath = "model_ncnn_model/model.ncnn.bin";

float g_confidence = 0.6f;
float g_iou = 0.45f;
int g_input_size = 1280;
std::string g_status = "NCNN stub";

#ifdef PJSK_WITH_NCNN
std::mutex g_mutex;
ncnn::Net g_net;
bool g_loaded = false;
bool g_gpu_initialized = false;
bool g_gpu_enabled = false;
int g_gpu_count = 0;
#endif

struct Proposal {
    float x1;
    float y1;
    float x2;
    float y2;
    float score;
    int label;
};

float clampf(float value, float low, float high) {
    return std::max(low, std::min(high, value));
}

float intersectionOverUnion(const Proposal& a, const Proposal& b) {
    const float x1 = std::max(a.x1, b.x1);
    const float y1 = std::max(a.y1, b.y1);
    const float x2 = std::min(a.x2, b.x2);
    const float y2 = std::min(a.y2, b.y2);
    const float w = std::max(0.0f, x2 - x1);
    const float h = std::max(0.0f, y2 - y1);
    const float inter = w * h;
    const float areaA = std::max(0.0f, a.x2 - a.x1) * std::max(0.0f, a.y2 - a.y1);
    const float areaB = std::max(0.0f, b.x2 - b.x1) * std::max(0.0f, b.y2 - b.y1);
    const float denom = areaA + areaB - inter;
    if (denom <= 0.0f) {
        return 0.0f;
    }
    return inter / denom;
}

std::vector<Proposal> nms(std::vector<Proposal>& proposals, float iouThreshold) {
    std::sort(proposals.begin(), proposals.end(),
              [](const Proposal& a, const Proposal& b) {
                  return a.score > b.score;
              });

    std::vector<Proposal> picked;
    picked.reserve(std::min<size_t>(proposals.size(), 100));
    for (const Proposal& p : proposals) {
        bool keep = true;
        for (const Proposal& kept : picked) {
            if (p.label == kept.label && intersectionOverUnion(p, kept) > iouThreshold) {
                keep = false;
                break;
            }
        }
        if (keep) {
            picked.push_back(p);
            if (picked.size() >= 100) {
                break;
            }
        }
    }
    return picked;
}

jfloatArray proposalsToArray(JNIEnv* env, const std::vector<Proposal>& proposals) {
    std::vector<float> raw;
    raw.reserve(proposals.size() * 6);
    for (const Proposal& p : proposals) {
        raw.push_back(p.x1);
        raw.push_back(p.y1);
        raw.push_back(p.x2);
        raw.push_back(p.y2);
        raw.push_back(static_cast<float>(p.label));
        raw.push_back(p.score);
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(raw.size()));
    if (result != nullptr && !raw.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(raw.size()), raw.data());
    }
    return result;
}

#ifdef PJSK_WITH_NCNN

float outputValue(const ncnn::Mat& out, int channel, int index) {
    if (out.dims == 2 && out.h >= kClassCount + 4) {
        return out.row(channel)[index];
    }
    if (out.dims == 2 && out.w >= kClassCount + 4) {
        return out.row(index)[channel];
    }
    if (out.dims == 3 && out.h >= kClassCount + 4) {
        return out.channel(0).row(channel)[index];
    }
    if (out.dims == 3 && out.w >= kClassCount + 4) {
        return out.channel(0).row(index)[channel];
    }
    return 0.0f;
}

int outputCount(const ncnn::Mat& out) {
    if (out.dims == 2 && out.h >= kClassCount + 4) {
        return out.w;
    }
    if (out.dims == 2 && out.w >= kClassCount + 4) {
        return out.h;
    }
    if (out.dims == 3 && out.h >= kClassCount + 4) {
        return out.w;
    }
    if (out.dims == 3 && out.w >= kClassCount + 4) {
        return out.h;
    }
    return 0;
}

std::vector<Proposal> decodeYoloOutput(
        const ncnn::Mat& out,
        int imageW,
        int imageH,
        float scale,
        int padLeft,
        int padTop) {
    std::vector<Proposal> proposals;
    int count = outputCount(out);
    proposals.reserve(128);

    for (int i = 0; i < count; i++) {
        float bestScore = 0.0f;
        int bestClass = -1;
        for (int c = 0; c < kClassCount; c++) {
            float score = outputValue(out, 4 + c, i);
            if (score > bestScore) {
                bestScore = score;
                bestClass = c;
            }
        }
        if (bestScore < g_confidence || bestClass < 0) {
            continue;
        }

        const float cx = outputValue(out, 0, i);
        const float cy = outputValue(out, 1, i);
        const float bw = outputValue(out, 2, i);
        const float bh = outputValue(out, 3, i);

        float x1 = (cx - bw * 0.5f - padLeft) / scale;
        float y1 = (cy - bh * 0.5f - padTop) / scale;
        float x2 = (cx + bw * 0.5f - padLeft) / scale;
        float y2 = (cy + bh * 0.5f - padTop) / scale;

        Proposal p{};
        p.x1 = clampf(x1, 0.0f, static_cast<float>(imageW - 1));
        p.y1 = clampf(y1, 0.0f, static_cast<float>(imageH - 1));
        p.x2 = clampf(x2, 0.0f, static_cast<float>(imageW - 1));
        p.y2 = clampf(y2, 0.0f, static_cast<float>(imageH - 1));
        p.score = bestScore;
        p.label = bestClass;
        if (p.x2 > p.x1 && p.y2 > p.y1) {
            proposals.push_back(p);
        }
    }
    return proposals;
}

#endif

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pjsk_autoplayer_ncnn_NcnnDetector_nativeInit(
        JNIEnv* env,
        jobject,
        jobject assetManager,
        jfloat confidence,
        jfloat iou,
        jint inputSize) {
    g_confidence = confidence;
    g_iou = iou;
    g_input_size = std::max(320, std::min(1280, static_cast<int>(inputSize)));
    g_input_size = (g_input_size / 32) * 32;

#ifndef PJSK_WITH_NCNN
    (void) env;
    (void) assetManager;
    g_status = "NCNN SDK missing, detector stub active";
    return JNI_FALSE;
#else
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        g_status = "AAssetManager is null";
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    g_net.clear();
    g_net.opt.num_threads = 4;
    g_net.opt.use_fp16_packed = true;
    g_net.opt.use_fp16_storage = true;
    g_net.opt.use_fp16_arithmetic = false;

#if NCNN_VULKAN
    if (!g_gpu_initialized) {
        g_gpu_initialized = ncnn::create_gpu_instance() == 0;
    }
    g_gpu_count = g_gpu_initialized ? ncnn::get_gpu_count() : 0;
    g_gpu_enabled = g_gpu_count > 0;
    g_net.opt.use_vulkan_compute = g_gpu_enabled;
    if (g_gpu_enabled) {
        g_net.set_vulkan_device(ncnn::get_default_gpu_index());
    }
#else
    g_gpu_count = 0;
    g_gpu_enabled = false;
    g_net.opt.use_vulkan_compute = false;
#endif

    if (g_net.load_param(mgr, kParamPath) != 0) {
        g_status = "failed to load model.ncnn.param";
        g_loaded = false;
        return JNI_FALSE;
    }
    if (g_net.load_model(mgr, kBinPath) != 0) {
        g_status = "failed to load model.ncnn.bin";
        g_loaded = false;
        return JNI_FALSE;
    }
    g_loaded = true;
    g_status = "NCNN loaded input=" + std::to_string(g_input_size)
            + " gpu=" + (g_gpu_enabled ? "on" : "off")
            + " gpu_count=" + std::to_string(g_gpu_count);
    return JNI_TRUE;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_pjsk_autoplayer_ncnn_NcnnDetector_nativeDetect(
        JNIEnv* env,
        jobject,
        jobject bitmap) {
#ifndef PJSK_WITH_NCNN
    (void) bitmap;
    return env->NewFloatArray(0);
#else
    if (!g_loaded || bitmap == nullptr) {
        return env->NewFloatArray(0);
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS
            || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return env->NewFloatArray(0);
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS
            || pixels == nullptr) {
        return env->NewFloatArray(0);
    }

    const int imageW = static_cast<int>(info.width);
    const int imageH = static_cast<int>(info.height);
    const int inputSize = g_input_size;
    const float scale = std::min(inputSize / static_cast<float>(imageW),
                                 inputSize / static_cast<float>(imageH));
    const int resizedW = static_cast<int>(std::round(imageW * scale));
    const int resizedH = static_cast<int>(std::round(imageH * scale));
    const int padLeft = (inputSize - resizedW) / 2;
    const int padRight = inputSize - resizedW - padLeft;
    const int padTop = (inputSize - resizedH) / 2;
    const int padBottom = inputSize - resizedH - padTop;

    ncnn::Mat input = ncnn::Mat::from_pixels_resize(
            static_cast<const unsigned char*>(pixels),
            ncnn::Mat::PIXEL_RGBA2RGB,
            imageW,
            imageH,
            resizedW,
            resizedH);
    AndroidBitmap_unlockPixels(env, bitmap);

    ncnn::Mat padded;
    ncnn::copy_make_border(
            input,
            padded,
            padTop,
            padBottom,
            padLeft,
            padRight,
            ncnn::BORDER_CONSTANT,
            114.0f);

    const float normVals[3] = {1.0f / 255.0f, 1.0f / 255.0f, 1.0f / 255.0f};
    padded.substract_mean_normalize(nullptr, normVals);

    ncnn::Mat out;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        ncnn::Extractor ex = g_net.create_extractor();
        ex.set_light_mode(true);
        if (ex.input("in0", padded) != 0) {
            return env->NewFloatArray(0);
        }
        if (ex.extract("out0", out) != 0) {
            return env->NewFloatArray(0);
        }
    }

    std::vector<Proposal> proposals = decodeYoloOutput(out, imageW, imageH, scale, padLeft, padTop);
    std::vector<Proposal> picked = nms(proposals, g_iou);
    return proposalsToArray(env, picked);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pjsk_autoplayer_ncnn_NcnnDetector_nativeStatus(
        JNIEnv* env,
        jobject) {
    return env->NewStringUTF(g_status.c_str());
}
