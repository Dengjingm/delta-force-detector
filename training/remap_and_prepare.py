#!/usr/bin/env python3
"""
把 incoming 候选 YOLO 数据整理成本地训练目录。

默认保留原始两类，不再把 head 和 person 合成 enemy：
    0: head
    1: person（身体框）

输出默认写到 data/head_body/，不覆盖正在使用的单类
data/images 与 data/labels（避免打断进行中的 yolo_research 训练）。

旧行为可用 --merge-enemy 把所有 class_id 写成 0，并输出到 data/。
"""
from __future__ import annotations

import argparse
import os
import shutil
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INCOMING = ROOT / "data" / "incoming"
DATA = ROOT / "data"

SPLIT_MAP = {"train": "train", "valid": "val", "val": "val", "test": "test"}
KEEP_CLASSES = {"0": "head", "1": "person"}


def find_dataset_roots() -> list[Path]:
    found = []
    for p in INCOMING.rglob("data.yaml"):
        found.append(p.parent)
    if not found:
        for p in INCOMING.rglob("*"):
            if p.is_dir() and ((p / "train").is_dir() or (p / "images" / "train").is_dir()):
                found.append(p)
    uniq = sorted(set(found), key=lambda p: (len(p.parts), str(p)))
    filtered = []
    for path in uniq:
        nested = False
        for other in uniq:
            if other == path:
                continue
            try:
                other.relative_to(path)
                nested = True
                break
            except ValueError:
                pass
        if not nested:
            filtered.append(path)
    return filtered


def find_dataset_root() -> Path:
    roots = find_dataset_roots()
    if not roots:
        raise SystemExit(f"未在 {INCOMING} 找到数据集根目录(缺 data.yaml 或 train/)")
    return roots[0]


def _split_aliases(src_split: str) -> tuple[str, ...]:
    if src_split in ("valid", "val"):
        return ("val", "valid", "Val", "Valid")
    return (src_split, src_split.capitalize())


def locate_split_dirs(root: Path):
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


def remap_label_text(text: str, *, merge_enemy: bool, src_name: str) -> tuple[str, Counter, int]:
    """返回 (写出文本, 各类计数, 跳过行数)。"""
    counts: Counter = Counter()
    skipped = 0
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 5:
            skipped += 1
            print(f"  [WARN] 跳过非法标签行 {src_name}: {line}")
            continue
        class_id = parts[0]
        if merge_enemy:
            parts[0] = "0"
            counts["0"] += 1
        else:
            if class_id not in KEEP_CLASSES:
                skipped += 1
                print(f"  [WARN] 跳过未知类别 {class_id} {src_name}: {line}")
                continue
            counts[class_id] += 1
        out.append(" ".join(parts))
    body = "\n".join(out) + ("\n" if out else "")
    return body, counts, skipped


def write_label(src: Path, dst: Path, *, merge_enemy: bool) -> Counter:
    body, counts, _ = remap_label_text(src.read_text(), merge_enemy=merge_enemy, src_name=str(src))
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(body)
    return counts


def link_or_copy(src: Path, dst: Path) -> None:
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists():
        if dst.samefile(src):
            return
        dst.unlink()
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def unique_name(dst_dir: Path, name: str, used: set[str]) -> str:
    if name not in used and not (dst_dir / name).exists():
        used.add(name)
        return name
    stem = Path(name).stem
    suffix = Path(name).suffix
    i = 2
    while True:
        cand = f"{stem}__{i}{suffix}"
        if cand not in used and not (dst_dir / cand).exists():
            used.add(cand)
            return cand
        i += 1


def write_dataset_yaml(out_root: Path, *, merge_enemy: bool) -> Path:
    yaml_path = out_root / "dataset.yaml"
    if merge_enemy:
        yaml_path.write_text(
            "# 单类 enemy（旧 MVP 合并）。下一轮训练请改用 head/person 目录。\n"
            "path: .\n"
            "train: images/train\n"
            "val: images/val\n"
            "nc: 1\n"
            "names:\n"
            "  0: enemy\n"
        )
    else:
        yaml_path.write_text(
            "# head / person 分开，不再合成 enemy。\n"
            "# 0: head（头）  1: person（身体）\n"
            "path: .\n"
            "train: images/train\n"
            "val: images/val\n"
            "nc: 2\n"
            "names:\n"
            "  0: head\n"
            "  1: person\n"
        )
    return yaml_path


def prepare_one(root: Path, out_root: Path, *, merge_enemy: bool, used_names: dict[str, set[str]]) -> tuple[int, Counter]:
    print(f"[INFO] 数据集根目录: {root}")
    splits = locate_split_dirs(root)
    if "train" not in splits:
        raise SystemExit(f"未在 {root} 找到 train 的 images+labels 结构")
    total_img = 0
    box_counts: Counter = Counter()
    for dst_split in ("train", "val", "test"):
        if dst_split not in splits:
            print(f"[WARN] 缺少 {dst_split} 划分,跳过")
            continue
        img_src, lbl_src = splits[dst_split]
        img_dst = out_root / "images" / dst_split
        lbl_dst = out_root / "labels" / dst_split
        used = used_names.setdefault(dst_split, set())
        imgs = sorted(p for p in img_src.iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))
        count = 0
        for img in imgs:
            name = unique_name(img_dst, img.name, used)
            link_or_copy(img, img_dst / name)
            lbl = lbl_src / (img.stem + ".txt")
            dst_lbl = lbl_dst / (Path(name).stem + ".txt")
            if lbl.is_file():
                box_counts.update(write_label(lbl, dst_lbl, merge_enemy=merge_enemy))
            else:
                dst_lbl.parent.mkdir(parents=True, exist_ok=True)
                dst_lbl.write_text("")
                print(f"  [WARN] 图片无标签: {img.name}")
            count += 1
        total_img += count
        print(f"[OK] {img_src} -> {dst_split}: {count} 张")
    return total_img, box_counts


def main() -> int:
    parser = argparse.ArgumentParser(description="整理候选 YOLO 数据；默认保留 head/person")
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help="incoming 下某一套候选集根目录",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="处理 incoming 下每一套候选集（写入同一输出目录）",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="输出根目录。默认 data/head_body；--merge-enemy 时默认 data/",
    )
    parser.add_argument(
        "--merge-enemy",
        action="store_true",
        help="旧行为：所有类别写成 enemy=0，输出到 data/",
    )
    args = parser.parse_args()
    merge = args.merge_enemy
    if args.out is not None:
        out_root = args.out.resolve()
    else:
        out_root = (DATA / "head_body").resolve() if not merge else DATA.resolve()
    if args.all:
        roots = find_dataset_roots()
        if not roots:
            raise SystemExit(f"未在 {INCOMING} 找到数据集")
    elif args.root is not None:
        roots = [args.root.resolve()]
    else:
        roots = [find_dataset_root()]

    print(f"[INFO] 输出: {out_root}")
    print(f"[INFO] 类别: {'enemy=0 合并' if merge else '0=head 1=person'}")
    total_img = 0
    box_counts: Counter = Counter()
    used_names: dict[str, set[str]] = {}
    for root in roots:
        if not root.is_dir():
            raise SystemExit(f"数据集根目录不存在: {root}")
        n, counts = prepare_one(root, out_root, merge_enemy=merge, used_names=used_names)
        total_img += n
        box_counts.update(counts)

    yaml_path = write_dataset_yaml(out_root, merge_enemy=merge)
    print(f"\n[INFO] 完成,共处理 {total_img} 张图片")
    if merge:
        print(f"[INFO] 框: enemy={box_counts.get('0', 0)}")
    else:
        print(
            f"[INFO] 框: head={box_counts.get('0', 0)} person={box_counts.get('1', 0)}"
        )
    print(f"[INFO] yaml: {yaml_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
