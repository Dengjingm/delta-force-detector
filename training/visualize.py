"""
标注可视化脚本 — 校验 YOLO 格式标注是否正确
从 dataset.yaml 自动读取类别名和颜色。

用法:
  python visualize.py --image data/images/train/xxx.jpg
  python visualize.py --dir data/images/train    # 随机展示 5 张
"""

import argparse
import random
from pathlib import Path

import cv2
import yaml


def load_classes(dataset_yaml: str) -> tuple:
    """从 dataset.yaml 中读取类别列表"""
    with open(dataset_yaml) as f:
        cfg = yaml.safe_load(f)
    names_dict = cfg.get("names", {})
    if isinstance(names_dict, dict):
        classes = [names_dict[i] for i in sorted(names_dict.keys())]
    elif isinstance(names_dict, list):
        classes = names_dict
    else:
        classes = []
    return classes


def read_yolo_label(txt_path: str) -> list:
    boxes = []
    if not Path(txt_path).exists():
        return boxes
    with open(txt_path) as f:
        for line in f:
            parts = line.strip().split()
            if len(parts) >= 5:
                cls_id = int(parts[0])
                cx, cy, w, h = map(float, parts[1:5])
                boxes.append((cls_id, cx, cy, w, h))
    return boxes


def denormalize(box, img_w, img_h):
    cls_id, cx, cy, w, h = box
    x1 = int((cx - w / 2) * img_w)
    y1 = int((cy - h / 2) * img_h)
    x2 = int((cx + w / 2) * img_w)
    y2 = int((cy + h / 2) * img_h)
    return cls_id, x1, y1, x2, y2


def random_color(seed: int) -> tuple:
    r = hash(f"r{seed}") % 200 + 55
    g = hash(f"g{seed}") % 200 + 55
    b = hash(f"b{seed}") % 200 + 55
    return (b, g, r)  # OpenCV uses BGR


def visualize_image(image_path: str, classes: list):
    img = cv2.imread(image_path)
    if img is None:
        print(f"[ERROR] 无法读取图片: {image_path}")
        return True
    h, w = img.shape[:2]

    txt_path = Path(image_path)
    label_dir = txt_path.parent.parent / "labels" / txt_path.parent.name
    txt_file = label_dir / (txt_path.stem + ".txt")
    boxes = read_yolo_label(str(txt_file))

    print(f"  {txt_path.name}  ({w}x{h})  labels: {len(boxes)}")
    for cls_id, cx, cy, bw, bh in boxes:
        _, x1, y1, x2, y2 = denormalize((cls_id, cx, cy, bw, bh), w, h)
        label = classes[cls_id] if cls_id < len(classes) else f"class_{cls_id}"
        color = random_color(cls_id)

        cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
        cv2.putText(img, label, (x1, y1 - 5),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, color, 2)

    cv2.imshow(f"Label Check: {txt_path.name}", img)
    print("  [any key] continue  [ESC] exit")
    key = cv2.waitKey(0)
    cv2.destroyAllWindows()
    if key == 27:
        return False
    return True


def main():
    parser = argparse.ArgumentParser(description="YOLO 标注可视化检查")
    parser.add_argument("--yaml", type=str, default="data/dataset.yaml",
                        help="dataset.yaml 路径")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--image", type=str, help="单张图片路径")
    group.add_argument("--dir", type=str, help="图片目录（随机看 5 张）")
    args = parser.parse_args()

    classes = load_classes(args.yaml)
    print(f"Loaded {len(classes)} classes: {classes}")

    if args.image:
        visualize_image(args.image, classes)
    elif args.dir:
        img_dir = Path(args.dir)
        images = list(img_dir.glob("*.jpg")) + list(img_dir.glob("*.png"))
        if not images:
            print(f"[ERROR] {args.dir} 中没有 jpg/png 图片")
            return
        selected = random.sample(images, min(5, len(images)))
        for img_path in selected:
            if not visualize_image(str(img_path), classes):
                break


if __name__ == "__main__":
    main()