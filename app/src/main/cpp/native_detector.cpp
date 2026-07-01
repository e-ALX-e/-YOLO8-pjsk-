#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>

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
    return denom <= 0.0f ? 0.0f : inter / denom;
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

struct DetectorContext {
    std::mutex mutex;
    ncnn::Net net;
    bool loaded = false;
    bool gpuEnabled = false;
    int gpuCount = 0;
    int classCount = 1;
    float confidence = 0.6f;
    float iou = 0.45f;
    int inputSize = 640;
    std::string status = "NCNN not loaded";
};

std::mutex g_gpu_mutex;
bool g_gpu_initialized = false;

float outputValue(const ncnn::Mat& out, int classCount, int channel, int index) {
    const int outputWidth = classCount + 4;
    if (out.dims == 2 && out.h >= outputWidth) {
        return out.row(channel)[index];
    }
    if (out.dims == 2 && out.w >= outputWidth) {
        return out.row(index)[channel];
    }
    if (out.dims == 3 && out.h >= outputWidth) {
        return out.channel(0).row(channel)[index];
    }
    if (out.dims == 3 && out.w >= outputWidth) {
        return out.channel(0).row(index)[channel];
    }
    return 0.0f;
}

int outputCount(const ncnn::Mat& out, int classCount) {
    const int outputWidth = classCount + 4;
    if (out.dims == 2 && out.h >= outputWidth) {
        return out.w;
    }
    if (out.dims == 2 && out.w >= outputWidth) {
        return out.h;
    }
    if (out.dims == 3 && out.h >= outputWidth) {
        return out.w;
    }
    if (out.dims == 3 && out.w >= outputWidth) {
        return out.h;
    }
    return 0;
}

std::vector<Proposal> decodeYoloOutput(
        DetectorContext* ctx,
        const ncnn::Mat& out,
        int imageW,
        int imageH,
        float scale,
        int padLeft,
        int padTop) {
    std::vector<Proposal> proposals;
    int count = outputCount(out, ctx->classCount);
    proposals.reserve(128);

    for (int i = 0; i < count; i++) {
        float bestScore = 0.0f;
        int bestClass = -1;
        for (int c = 0; c < ctx->classCount; c++) {
            float score = outputValue(out, ctx->classCount, 4 + c, i);
            if (score > bestScore) {
                bestScore = score;
                bestClass = c;
            }
        }
        if (bestScore < ctx->confidence || bestClass < 0) {
            continue;
        }

        const float cx = outputValue(out, ctx->classCount, 0, i);
        const float cy = outputValue(out, ctx->classCount, 1, i);
        const float bw = outputValue(out, ctx->classCount, 2, i);
        const float bh = outputValue(out, ctx->classCount, 3, i);

        Proposal p{};
        p.x1 = clampf((cx - bw * 0.5f - padLeft) / scale, 0.0f, static_cast<float>(imageW - 1));
        p.y1 = clampf((cy - bh * 0.5f - padTop) / scale, 0.0f, static_cast<float>(imageH - 1));
        p.x2 = clampf((cx + bw * 0.5f - padLeft) / scale, 0.0f, static_cast<float>(imageW - 1));
        p.y2 = clampf((cy + bh * 0.5f - padTop) / scale, 0.0f, static_cast<float>(imageH - 1));
        p.score = bestScore;
        p.label = bestClass;
        if (p.x2 > p.x1 && p.y2 > p.y1) {
            proposals.push_back(p);
        }
    }
    return proposals;
}

#endif

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return "";
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? "" : chars;
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_pjsk_autoplayer_ncnn_NcnnDetector_nativeCreate(
        JNIEnv* env,
        jobject,
        jobject assetManager,
        jstring paramPath,
        jstring binPath,
        jint classCount,
        jfloat confidence,
        jfloat iou,
        jint inputSize) {
#ifndef PJSK_WITH_NCNN
    (void) env;
    (void) assetManager;
    (void) paramPath;
    (void) binPath;
    (void) classCount;
    (void) confidence;
    (void) iou;
    (void) inputSize;
    return 0;
#else
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (mgr == nullptr) {
        return 0;
    }

    DetectorContext* ctx = new DetectorContext();
    ctx->classCount = std::max(1, static_cast<int>(classCount));
    ctx->confidence = confidence;
    ctx->iou = iou;
    ctx->inputSize = std::max(320, std::min(1280, static_cast<int>(inputSize)));
    ctx->inputSize = (ctx->inputSize / 32) * 32;

    ctx->net.opt.num_threads = 4;
    ctx->net.opt.use_fp16_packed = true;
    ctx->net.opt.use_fp16_storage = true;
    ctx->net.opt.use_fp16_arithmetic = false;

#if NCNN_VULKAN
    {
        std::lock_guard<std::mutex> lock(g_gpu_mutex);
        if (!g_gpu_initialized) {
            g_gpu_initialized = ncnn::create_gpu_instance() == 0;
        }
    }
    ctx->gpuCount = g_gpu_initialized ? ncnn::get_gpu_count() : 0;
    ctx->gpuEnabled = ctx->gpuCount > 0;
    ctx->net.opt.use_vulkan_compute = ctx->gpuEnabled;
    if (ctx->gpuEnabled) {
        ctx->net.set_vulkan_device(ncnn::get_default_gpu_index());
    }
#else
    ctx->gpuCount = 0;
    ctx->gpuEnabled = false;
    ctx->net.opt.use_vulkan_compute = false;
#endif

    std::string param = jstringToString(env, paramPath);
    std::string bin = jstringToString(env, binPath);
    if (ctx->net.load_param(mgr, param.c_str()) != 0) {
        ctx->status = "failed to load " + param;
        delete ctx;
        return 0;
    }
    if (ctx->net.load_model(mgr, bin.c_str()) != 0) {
        ctx->status = "failed to load " + bin;
        delete ctx;
        return 0;
    }
    ctx->loaded = true;
    ctx->status = "NCNN loaded input=" + std::to_string(ctx->inputSize)
            + " classes=" + std::to_string(ctx->classCount)
            + " gpu=" + (ctx->gpuEnabled ? "on" : "off")
            + " gpu_count=" + std::to_string(ctx->gpuCount);
    return reinterpret_cast<jlong>(ctx);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_pjsk_autoplayer_ncnn_NcnnDetector_nativeRelease(
        JNIEnv*,
        jobject,
        jlong handle) {
#ifdef PJSK_WITH_NCNN
    DetectorContext* ctx = reinterpret_cast<DetectorContext*>(handle);
    delete ctx;
#else
    (void) handle;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_pjsk_autoplayer_ncnn_NcnnDetector_nativeDetect(
        JNIEnv* env,
        jobject,
        jlong handle,
        jobject bitmap) {
#ifndef PJSK_WITH_NCNN
    (void) handle;
    (void) bitmap;
    return env->NewFloatArray(0);
#else
    DetectorContext* ctx = reinterpret_cast<DetectorContext*>(handle);
    if (ctx == nullptr || !ctx->loaded || bitmap == nullptr) {
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
    const int inputSize = ctx->inputSize;
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
        std::lock_guard<std::mutex> lock(ctx->mutex);
        ncnn::Extractor ex = ctx->net.create_extractor();
        ex.set_light_mode(true);
        if (ex.input("in0", padded) != 0) {
            return env->NewFloatArray(0);
        }
        if (ex.extract("out0", out) != 0) {
            return env->NewFloatArray(0);
        }
    }

    std::vector<Proposal> proposals = decodeYoloOutput(ctx, out, imageW, imageH, scale, padLeft, padTop);
    std::vector<Proposal> picked = nms(proposals, ctx->iou);
    return proposalsToArray(env, picked);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pjsk_autoplayer_ncnn_NcnnDetector_nativeStatus(
        JNIEnv* env,
        jobject,
        jlong handle) {
#ifdef PJSK_WITH_NCNN
    DetectorContext* ctx = reinterpret_cast<DetectorContext*>(handle);
    if (ctx == nullptr) {
        return env->NewStringUTF("NCNN unavailable");
    }
    return env->NewStringUTF(ctx->status.c_str());
#else
    (void) handle;
    return env->NewStringUTF("NCNN SDK missing, detector stub active");
#endif
}
