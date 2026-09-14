#!/usr/bin/env python3
"""
把 Roboflow 候选数据(head / person)重映射为单类 enemy,并重组到标准目录。

MVP 语义简化:head(0) 与 person(1) 全部合并为 enemy(0)。
这不等价于「enemy」的最终语义复核,仅用于跑通训练管线并产出首个权重。
正式语义规则(队友/尸体/遮挡/不确定小点)需后续复核后回填。

输入支持两种 YOLO 导出布局:
    Roboflow:    training/data/incoming/<root>/{train,valid,test}/{images,labels}
    Ultralytics: training/data/incoming/<root>/{images,labels}/{train,val,test}

可用 --root 指定 incoming 下的某一套候选集;不指定时取 incoming 中最浅的 data.yaml。

输出:
    training/data/images/{train,val,test}
    training/data/labels/{train,val,test}

映射:
    train -> train, valid/val -> val, test -> test(保留独立测试,dataset.yaml 不引用 test)
    class_id 任意 -> 0 (enemy)
"""
import argparse
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INCOMING = ROOT / "data" / "incoming"
DATA = ROOT / "data"

# 源划分名 -> 输出划分名。valid 与 val 视为同一划分。
SPLIT_MAP = {"train": "train", "valid": "val", "val": "val", "test": "test"}


def find_dataset_root() -> Path:
    """定位 incoming 下解压出的数据集根目录(含 data.yaml 或含 train/ 子目录)。"""
    candidates = []
    for p in INCOMING.rglob("data.yaml"):
        candidates.append(p.parent)
    if candidates:
        # 优先选最浅的那个(通常是解压根)
        return sorted(candidates, key=lambda p: len(p.parts))[0]

    for p in INCOMING.rglob("*"):
        if p.is_dir() and ((p / "train").is_dir() or (p / "images" / "train").is_dir()):
            candidates.append(p)
    if candidates:
        return sorted(candidates, key=lambda p: len(p.parts))[0]

    raise SystemExit(f"未在 {INCOMING} 找到数据集根目录(缺 data.yaml 或 train/)")


def _split_aliases(src_split: str) -> tuple[str, ...]:
    if src_split in ("valid", "val"):
        return ("val", "valid", "Val", "Valid")
    return (src_split, src_split.capitalize())


def locate_split_dirs(root: Path):
    """返回 {输出划分: (images_dir, labels_dir)}。

    支持 Roboflow `{split}/images` 与 Ultralytics `images/{split}` 两种布局。
    """
    out = {}
    for src_split, dst_split in SPLIT_MAP.items():
        if dst_split in out:
            continue
        for name in _split_aliases(src_split):
            robo_img = root / name / "images"
            robo_lbl = root / name / "labels"
            if robo_img.is_dir() and robo_lbl.is_dir():
                out[dst_split] = (robo_img, robo_lbl)
                break
            ultra_img = root / "images" / name
            ultra_lbl = root / "labels" / name
            if ultra_img.is_dir() and ultra_lbl.is_dir():
                out[dst_split] = (ultra_img, ultra_lbl)
                break
    return out


def remap_label(src: Path, dst: Path) -> None:
    """重映射:所有 class_id -> 0。同时跳过空行/注释。"""
    lines = src.read_text().splitlines()
    out = []
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 5:
            print(f"  [WARN] 跳过非法标签行 {src}: {line}")
            continue
        parts[0] = "0"
        out.append(" ".join(parts))
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text("\n".join(out) + ("\n" if out else ""))


def link_or_copy(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        return
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def main() -> int:
    parser = argparse.ArgumentParser(description="重映射候选 YOLO 数据为单类 enemy")
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help="incoming 下某一套候选集根目录;默认自动寻找最浅的 data.yaml",
    )
    args = parser.parse_args()
    root = args.root.resolve() if args.root else find_dataset_root()
    if not root.is_dir():
        raise SystemExit(f"数据集根目录不存在: {root}")
    print(f"[INFO] 数据集根目录: {root}")
    splits = locate_split_dirs(root)
    if "train" not in splits:
        raise SystemExit(f"未在 {root} 找到 train 的 images+labels 结构")

    total_img = 0
    for dst_split in ("train", "val", "test"):
        if dst_split not in splits:
            print(f"[WARN] 缺少 {dst_split} 划分,跳过")
            continue
        img_src, lbl_src = splits[dst_split]
        img_dst = DATA / "images" / dst_split
        lbl_dst = DATA / "labels" / dst_split

        imgs = sorted(img_src.glob("*"))
        count = 0
        for img in imgs:
            if img.suffix.lower() not in (".jpg", ".jpeg", ".png"):
                continue
            link_or_copy(img, img_dst / img.name)
            lbl = lbl_src / (img.stem + ".txt")
            if lbl.is_file():
                remap_label(lbl, lbl_dst / lbl.name)
            else:
                print(f"  [WARN] 图片无标签: {img.name}")
            count += 1
        total_img += count
        print(f"[OK] {img_src} -> {dst_split}: {count} 张")

    print(f"\n[INFO] 完成,共处理 {total_img} 张图片")
    print(f"[INFO] 输出: {DATA / 'images'}, {DATA / 'labels'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
