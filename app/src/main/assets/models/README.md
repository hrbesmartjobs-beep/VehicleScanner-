# Plate detection model slot

Put a YOLO licence-plate detector here named exactly:

    plate_detector.tflite

It will be bundled in the APK and used automatically. Without it the app uses its
built-in OpenCV classical detector, and you can also import a model at runtime
from **Settings → Plate detection model → Import .tflite** (no rebuild needed).

Requirements: single-class "licence plate" model exported from Ultralytics
YOLOv5 / YOLOv8 / YOLO11 / YOLO26 with `format=tflite` (float32, float16 or int8).
See `tools/train_plate_detector.py` in the project root.
