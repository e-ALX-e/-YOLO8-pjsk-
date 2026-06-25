# PJSK Native Auto Player

这是手机端原生方案的工程骨架，目标是把原来电脑端的 `scrcpy + Python + ADB touch` 换成：

- Android `MediaProjection` 手机本地抓屏
- NCNN 手机本地推理
- root 下 `/dev/input/event*` 触摸注入
- Java 版轨迹追踪和动作判定

## 当前状态

已迁移：

- 录屏服务
- 模型 JNI 接口
- YOLOv8 NCNN 输出解码和 NMS
- Python 版 `Config` 参数
- Python 版 `LaneTracker` 核心追踪逻辑
- Python 版 tap / hold / sweep / flick 动作逻辑
- root `/dev/input/event*` 二进制触摸注入第一版

还需要本机环境：

- Android Studio
- Android SDK 36
- Android NDK
- CMake
- NCNN Android SDK

## 放置 NCNN SDK

下载 NCNN Android Vulkan SDK 后，把 arm64-v8a 的头文件和库放到：

```text
app/src/main/cpp/ncnn/arm64-v8a/include/ncnn/net.h
app/src/main/cpp/ncnn/arm64-v8a/lib/libncnn.a
```

如果没有放 NCNN SDK，工程仍然可以同步，但检测器会以空检测桩运行。

## 模型资源

模型文件会放在：

```text
app/src/main/assets/model_ncnn_model/model.ncnn.param
app/src/main/assets/model_ncnn_model/model.ncnn.bin
```

## 运行

1. 用 Android Studio 打开 `android_native`
2. 安装 SDK / NDK / CMake
3. 放入 NCNN SDK
4. Build APK
5. 安装到已 root 手机
6. 打开 App，点“开始录屏”
7. 授权 root 和录屏
8. 切回游戏

第一版 root 触控使用常驻 `su -c "cat > /dev/input/eventX"`，App 直接写 Linux `input_event` 二进制事件流。它已经避开 ADB 和逐条 `sendevent` 命令；如果实测仍然不够快，下一步再把触摸层换成 root native daemon。
