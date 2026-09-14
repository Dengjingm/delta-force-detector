#!/usr/bin/env python3
"""
把 Roboflow 候选数据(head / person)重映射为单类 enemy,并重组到标准目录。

MVP 语义简化:head(0) 与 person(1) 全部合并为 enemy(0)。
这不等价于「敌方干员」的最终语义复核,仅用于跑通训练管线并产出首个权重。
正式语义规则(队友/尸体/遮挡/不确定小点)需后续复核后回填。

输入(解压后的 Roboflow YOLOv8 导出):
    training/data/incoming/<root>/{train,valid,test}/{images,labels}

输出:
    training/data/images/{train,val,test}
    training/data/labels/{train,val,test}

映射:
    train -> train, valid -> val, test -> test(保留独立测试,dataset.yaml 不引用 test)
    class_id 任意 -> 0 (enemy)
"""
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INCOMING = ROOT / "data" / "incoming"
DATA = ROOT / "data"

SPLIT_MAP = {"train": "train", "valid": "val", "test": "test"}


def find_dataset_root() -> Path:
    """定位 incoming 下解压出的数据集根目录(含 data.yaml 或含 train/ 子目录)。"""
    candidates = []
    for p in INCOMING.rglob("data.yaml"):
        candidates.append(p.parent)
    if candidates:
        # 优先选最浅的那个(通常是解压根)
        return sorted(candidates, key=lambda p: len(p.parts))[0]

    for p in INCOMING.rglob("*"):
        if p.is_dir() and (p / "train").is_dir():
            candidates.append(p)
    if candidates:
        return sorted(candidates, key=lambda p: len(p.parts))[0]

    raise SystemExit(f"未在 {INCOMING} 找到数据集根目录(缺 data.yaml 或 train/)")


def locate_split_dirs(root: Path):
    """返回 {split_name: (images_dir, labels_dir)}。支持 {split}/images 与 {split}/labels 结构。"""
    out = {}
    for src_split, _dst in SPLIT_MAP.items():
        for split_dir in (root / src_split, root / src_split.capitalize()):
            img = split_dir / "images"
            lbl = split_dir / "labels"
            if img.is_dir() and lbl.is_dir():
                out[src_split] = (img, lbl)
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
    root = find_dataset_root()
    print(f"[INFO] 数据集根目录: {root}")
    splits = locate_split_dirs(root)
    if not splits:
        raise SystemExit(f"未在 {root} 找到 train/valid 的 images+labels 结构")

    total_img = 0
    for src_split, dst_split in SPLIT_MAP.items():
        if src_split not in splits:
            print(f"[WARN] 缺少 {src_split} 划分,跳过")
            continue
        img_src, lbl_src = splits[src_split]
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
        print(f"[OK] {src_split} -> {dst_split}: {count} 张")

    print(f"\n[INFO] 完成,共处理 {total_img} 张图片")
    print(f"[INFO] 输出: {DATA / 'images'}, {DATA / 'labels'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
