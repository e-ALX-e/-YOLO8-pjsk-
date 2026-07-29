from pathlib import Path
import shutil

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
DOWNLOADS = Path.home() / "Downloads"
DATASET = ROOT / "datasets" / "ui_buttons"

CLASSES = ["confirm_button", "play_button"]

CONFIRM_IMAGES = [
    "Screenshot_2026-06-28-20-54-15-079_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-25-404_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-27-207_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-28-610_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-29-824_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-31-064_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-32-830_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-34-020_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-35-296_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-37-229_com.hermes.mk..jpg",
]

PLAY_IMAGES = [
    "Screenshot_2026-07-01-13-42-40-080_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-43-250_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-45-065_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-46-480_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-48-126_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-49-737_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-51-177_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-52-233_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-54-551_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-42-56-613_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-43-08-313_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-43-10-573_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-43-12-643_com.hermes.mk..jpg",
    "Screenshot_2026-07-01-13-43-14-623_com.hermes.mk..jpg",
]


def normalized_box(image_size, cls_id):
    width, height = image_size
    if cls_id == 0:
        # 选歌页的“确定”按钮，覆盖按钮外框和文字区域。
        x_center = 1500 / 2048
        y_center = 768 / 946
        box_w = 240 / 2048
        box_h = 90 / 946
    else:
        # 准备演奏页的“播放”按钮，覆盖圆形按钮主体和三角图标。
        x_center = 1515 / 2048
        y_center = 720 / 946
        box_w = 180 / 2048
        box_h = 180 / 946
    return x_center, y_center, box_w, box_h


def write_yaml():
    data = (
        f"path: {DATASET.as_posix()}\n"
        "train: images/train\n"
        "val: images/val\n"
        "names:\n"
        "  0: confirm_button\n"
        "  1: play_button\n"
    )
    (DATASET / "data.yaml").write_text(data, encoding="utf-8")


def write_label(label_path, cls_id, image_size):
    x, y, w, h = normalized_box(image_size, cls_id)
    label_path.write_text(f"{cls_id} {x:.6f} {y:.6f} {w:.6f} {h:.6f}\n", encoding="utf-8")


def draw_preview(image_path, preview_path, cls_id):
    image = Image.open(image_path).convert("RGB")
    width, height = image.size
    x, y, w, h = normalized_box(image.size, cls_id)
    left = int((x - w / 2) * width)
    top = int((y - h / 2) * height)
    right = int((x + w / 2) * width)
    bottom = int((y + h / 2) * height)
    draw = ImageDraw.Draw(image)
    color = (40, 255, 120) if cls_id == 0 else (80, 180, 255)
    draw.rectangle([left, top, right, bottom], outline=color, width=5)
    draw.text((left, max(0, top - 24)), CLASSES[cls_id], fill=color)
    image.save(preview_path, quality=92)


def add_image(filename, cls_id, index):
    source = DOWNLOADS / filename
    if not source.exists():
        raise FileNotFoundError(source)

    split = "val" if index % 5 == 0 else "train"
    image_dir = DATASET / "images" / split
    label_dir = DATASET / "labels" / split
    preview_dir = DATASET / "previews" / split
    image_dir.mkdir(parents=True, exist_ok=True)
    label_dir.mkdir(parents=True, exist_ok=True)
    preview_dir.mkdir(parents=True, exist_ok=True)

    target_name = f"{CLASSES[cls_id]}_{index:03d}{source.suffix.lower()}"
    image_target = image_dir / target_name
    label_target = label_dir / f"{image_target.stem}.txt"
    preview_target = preview_dir / target_name

    shutil.copy2(source, image_target)
    image = Image.open(image_target)
    write_label(label_target, cls_id, image.size)
    draw_preview(image_target, preview_target, cls_id)


def main():
    if DATASET.exists():
        shutil.rmtree(DATASET)
    for index, filename in enumerate(CONFIRM_IMAGES):
        add_image(filename, 0, index)
    for index, filename in enumerate(PLAY_IMAGES):
        add_image(filename, 1, index)
    write_yaml()
    print(f"dataset: {DATASET}")
    print(f"classes: {CLASSES}")
    print(f"images: {len(CONFIRM_IMAGES) + len(PLAY_IMAGES)}")


if __name__ == "__main__":
    main()
