#!/usr/bin/env python3
"""
Train (or fine-tune) a YOLO licence-plate detector and export it to TensorFlow Lite
for Vehicle Scanner.

Run on a PC with an NVIDIA GPU or on Google Colab (free GPU):

    pip install -r tools/requirements.txt
    python tools/train_plate_detector.py --data path/to/data.yaml --epochs 80

Dataset: any YOLO-format dataset whose only class is the number plate. Good public
sources of Indian plate images (check each dataset's licence before commercial use):
  * Roboflow Universe – search "indian number plate" (many CC BY 4.0 datasets,
    download in "YOLOv8" format, which includes data.yaml)
  * Your own photos labelled with Label Studio / CVAT / Roboflow (best accuracy for
    your parking areas – 300-1000 labelled photos already help a lot).

Output: plate_detector.tflite – copy it to
    app/src/main/assets/models/plate_detector.tflite   (bundled), or
import it on the phone via Settings → Plate detection model → Import .tflite.

LICENCE NOTE: Ultralytics YOLO code and its pretrained weights are AGPL-3.0
(or Ultralytics Enterprise licence). Using a model you train with it inside an app you
distribute may require AGPL compliance or a commercial licence from Ultralytics.
For purely internal use this is usually not an issue – check with your legal team.
"""
import argparse
import shutil
from pathlib import Path

from ultralytics import YOLO


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="YOLO data.yaml with a single plate class")
    ap.add_argument("--model", default="yolov8n.pt", help="base weights: yolov8n.pt / yolo11n.pt (n = fastest on phones)")
    ap.add_argument("--epochs", type=int, default=80)
    ap.add_argument("--imgsz", type=int, default=640, help="must match the app (640 recommended)")
    ap.add_argument("--int8", action="store_true", help="int8 quantisation (smaller/faster, slightly less accurate)")
    ap.add_argument("--out", default="plate_detector.tflite")
    a = ap.parse_args()

    model = YOLO(a.model)
    model.train(data=a.data, epochs=a.epochs, imgsz=a.imgsz, batch=-1, patience=20,
                degrees=10, perspective=0.0005, fliplr=0.0,  # plates must not be mirrored
                hsv_v=0.5, mosaic=1.0, close_mosaic=10)
    best = Path(model.trainer.best)
    print("Best weights:", best)

    trained = YOLO(str(best))
    metrics = trained.val(data=a.data, imgsz=a.imgsz)
    print("mAP50:", metrics.box.map50, "mAP50-95:", metrics.box.map)

    exported = trained.export(format="tflite", imgsz=a.imgsz, int8=a.int8, data=a.data if a.int8 else None)
    exported = Path(exported)
    # Ultralytics writes a folder; pick the float32/int8 file it produced.
    if exported.is_dir():
        pattern = "*int8.tflite" if a.int8 else "*float32.tflite"
        exported = sorted(exported.glob(pattern))[0]
    shutil.copy(exported, a.out)
    print("Wrote", a.out)


if __name__ == "__main__":
    main()
