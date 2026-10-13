# 安卓 NCNN 接口约定

这些约定从用户本地安卓推理代码于 2026-10-13 整理，用于训练和导出时检查兼容性。它们不证明新模型已经通过实机测试。

## 输入

- 默认输入尺寸：640 × 640，单张图。
- 类别数 4，固定顺序 tap、hold、sweep、flick。
- NCNN 输入节点：in0；输出节点：out0。
- 安卓 Bitmap 使用 RGBA8888，送入网络前转换为 RGB。
- 等比例缩放：scale = min(640 / 原宽, 640 / 原高)。
- 缩放宽高使用 C++ round；左、上补边取剩余像素的一半向下取整，余数补到右、下。
- 方形 letterbox，补边像素值 114，不采用自动缩小到 stride 倍数的矩形输入。
- RGB 各通道除以 255，不额外减均值。

输入改为 480/320 时，导出形状与安卓配置需要同步修改。较小输入主要降低计算量，不会自动减少网络权重参数。

## 输出和后处理

检测头应提供每个候选的 8 个数：
center_x、center_y、width、height、tap_score、hold_score、sweep_score、flick_score。

前四项是 letterbox 输入图上的像素坐标；后四项是可直接比较的类别分数。当前解码器不再执行 sigmoid，也不单独乘 objectness；它不接受未经解码的 DFL 分布，也不能直接读取另一种 end-to-end 输出格式。

现有模型采用通道在前的 8 × N 输出。导出时必须实测 blob 形状及数值，不要仅凭文件扩展名判定兼容。

- 每个候选选择得分最高的类别。
- 默认置信度阈值：0.60。
- 减去 letterbox 偏移并除以 scale，得到原图坐标。
- 坐标限制到 0～原宽/原高减 1，丢弃非正面积框。
- 按分数降序执行同类别 NMS，IoU 阈值 0.45。
- 最多保留 100 个检测框。

标准 mAP 评估和实际安卓阈值下的 Precision/Recall 应分别报告。候选比较采用一致的预处理、阈值与 NMS；若在验证集调整阈值，固定后才进行最终测试。

## 基线及导出检查

- 原版：baselines/original_ncnn/model.ncnn.param + model.ncnn.bin。
- INT8：baselines/int8_ncnn/model.int8.param + model.int8.bin。
- 原版 metadata.yaml 中记录 Ultralytics 8.4.76、imgsz 640、end2end false。
- 新模型先保留浮点导出作对照，再评估当前 NCNN 工具链支持的量化方式。
- 分别验证 .pt、浮点 NCNN、量化 NCNN 的实际输出及检测指标。量化校准不能用保留测试集。
- 如果直接用 Python 包无法正确加载旧 INT8 模型，应检查图结构和 NCNN 支持，不得默默换成其他模型当作相同基线。

现有安卓设置启用 FP16 packed/storage，关闭 FP16 arithmetic；Vulkan 根据设备及模型加载情况启用或回退。INT8 文件更小并不保证目标手机一定更快，必须实测。

安卓验收需要真实设备上的预热后平均/P95 耗时、内存和持续运行表现。云端 CPU 数据只能用于同一云端环境中的相对比较。
