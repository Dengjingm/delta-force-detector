"""
YOLOv8nano 训练脚本
在 macOS 上运行，Apple Silicon 可通过 device="mps" 加速
"""

import os
import sys
from pathlib import Path

import torch
from ultralytics import YOLO


def train():
    # ── 配置区 ──────────────────────────────────────────
    project_root = Path(__file__).parent
    os.chdir(project_root)
    data_yaml = str(project_root / "data" / "dataset.yaml")
    model_name = str(project_root / "yolov8s.pt")  # MVP: 标准权重(P2 变体不可得,后续对齐)
    epochs = 50                     # MVP: 先跑通;正式训练再增加
    batch_size = 16
    imgsz = 640                     # MVP: 标准 yolov8s 输入;P2/960 后续对齐

    # Apple Silicon 用 MPS，否则 CPU
    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"[INFO] Using device: {device}")

    # 检查数据是否存在
    if not os.path.isfile(data_yaml):
        print(f"[ERROR] dataset.yaml not found at: {data_yaml}")
        print("[HINT]  请先在 training/data/images/train 中放入截图，")
        print("        并在 training/data/labels/train 中放入 YOLO 格式标注文件")
        sys.exit(1)

    # ── 加载模型 ────────────────────────────────────────
    model = YOLO(model_name)

    # ── 训练 ────────────────────────────────────────────
    results = model.train(
        data=data_yaml,
        epochs=epochs,
        batch=batch_size,
        imgsz=imgsz,
        device=device,
        workers=8,
        amp=True,
        project=str(project_root / "runs"),
        name="yolo_research",
        exist_ok=True,
        pretrained=True,
        optimizer="AdamW",
        lr0=0.001,
        lrf=0.01,
        warmup_epochs=3,
        cos_lr=True,
        patience=20,
        val=True,
        save=True,
        save_period=10,
    )

    # ── 保存最佳权重 ────────────────────────────────────
    best_pt = project_root / "runs" / "yolo_research" / "weights" / "best.pt"
    if best_pt.exists():
        print(f"\n[OK] 训练完成！最佳权重: {best_pt}")
        print(f"     验证 mAP50: {results.results_dict.get('metrics/mAP50(B)', 'N/A'):.3f}")
    else:
        print("[WARN] best.pt 未找到，请检查 runs/yolo_research/weights/ 目录")

    return results


if __name__ == "__main__":
    train()