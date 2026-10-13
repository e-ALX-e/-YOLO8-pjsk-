# 云端环境准备

只在当前订阅内已提供的云端环境执行。先确认 CPU、内存、磁盘、可用执行时限及文件路径，不创建第三方算力实例。

## 完整性检查

在仓库根目录运行以下只读检查，无需第三方依赖：

~~~bash
python - <<'PY'
import hashlib
import json
from pathlib import Path

root = Path.cwd()
manifest = json.loads((root / "MANIFEST.json").read_text(encoding="utf-8"))
for item in manifest["files"]:
    path = root / item["path"]
    assert path.is_file(), f"Missing: {path}"
    assert path.stat().st_size == item["bytes"], f"Size mismatch: {path}"
    assert hashlib.sha256(path.read_bytes()).hexdigest() == item["sha256"], f"Hash mismatch: {path}"
print("Verified", len(manifest["files"]), "asset files")
print(manifest["boxes_by_class"])
PY
~~~

## 可参考的兼容环境

上传前已用 Python 3.11.9、Ultralytics 8.4.83、PyTorch 2.5.1、torchvision 0.20.1 加载该 .pt。下面给出 CPU 环境准备命令；本次没有在云端执行安装，需以云端实际安装和验证结果为准。

~~~bash
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install --index-url https://download.pytorch.org/whl/cpu torch==2.5.1 torchvision==0.20.1
python -m pip install ultralytics==8.4.83
python -m pip check
~~~

如云环境的 Python 或系统依赖不兼容，先修复环境或选择兼容版本，保存最终依赖锁定记录；不要悄悄更换模型架构。NCNN 导出依赖按实际 Ultralytics 版本检查，需要时再安装并记录，不能宣称已验证不存在的导出流程。

## 只读模型加载检查

~~~bash
python - <<'PY'
from ultralytics import YOLO

model = YOLO("weights/note_yolov8n_640.pt")
assert model.names == {0: "tap", 1: "hold", 2: "sweep", 3: "flick"}
print("Parameters:", sum(p.numel() for p in model.model.parameters()))
print("Checkpoint epoch:", model.ckpt.get("epoch"))
print("Optimizer present:", model.ckpt.get("optimizer") is not None)
PY
~~~

接着按 CLOUD_TRAINING_PROMPT.md 建立数据分组、划分、基线及小规模试训。检查点 epoch=-1 且无优化器状态，应用于新一轮微调初始化，不使用 resume=True 恢复原任务。
