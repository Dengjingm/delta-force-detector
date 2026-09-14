"""
YOLOv8nano PyTorch → TFLite 导出脚本

用法:
  python export_tflite.py                              # 导出默认 best.pt
  python export_tflite.py --weights path/to/best.pt    # 指定权重
  python export_tflite.py --int8                        # 量化到 int8
"""

import argparse
from pathlib import Path

from ultralytics import YOLO


def export_tflite(weights: str, int8: bool = False):
    # ── 加载训练好的模型 ────────────────────────────────
    model = YOLO(weights)
    export_path = Path(weights).parent

    # ── 导出 TFLite ────────────────────────────────────
    # imgsz 必须与训练时一致 (MVP 用 640;对齐 P2/960 时同步改回)
    # int8 量化：体积更小、速度略快，但精度轻微下降
    # fp16 量化：精度几乎无损，体积减半
    model.export(
        format="tflite",
        imgsz=640,
        int8=int8,
        half=not int8,
        nms=True,         # 内置 NMS，后处理更简单
    )

    # ── 确认输出 ────────────────────────────────────────
    if int8:
        expected = export_path / "best_int8.tflite"
    else:
        expected = export_path / "best_fp16.tflite"

    # Ultralytics 导出的命名规则可能不带后缀
    candidates = list(export_path.glob("*.tflite"))
    if candidates:
        print(f"[OK] TFLite 模型导出完成:")
        for c in candidates:
            size_mb = c.stat().st_size / 1_000_000
            print(f"     {c.name}  ({size_mb:.1f} MB)")
    else:
        print(f"[WARN] 未找到 .tflite 文件，请检查 {export_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="YOLOv8nano → TFLite")
    parser.add_argument(
        "--weights",
        type=str,
        default="runs/hok_detector/weights/best.pt",
        help="训练好的 PyTorch 权重路径",
    )
    parser.add_argument(
        "--int8",
        action="store_true",
        help="使用 int8 量化（默认 fp16）",
    )
    args = parser.parse_args()
    export_tflite(args.weights, args.int8)