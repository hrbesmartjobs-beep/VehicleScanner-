# Third-party components

| Component | Use | Licence |
|---|---|---|
| OpenCV 4.12 (`org.opencv:opencv`) | Image enhancement, classical plate detector | Apache License 2.0 |
| LiteRT 1.4 (`com.google.ai.edge.litert:litert`, successor of TensorFlow Lite) | On-device YOLO inference | Apache License 2.0 |
| Google ML Kit Text Recognition v2, bundled (`com.google.mlkit:text-recognition`) | Offline OCR | ML Kit Terms of Service (free, on-device) |
| AndroidX, Jetpack Compose, Material 3, Room, Navigation, Lifecycle | App framework | Apache License 2.0 |
| Kotlin, kotlinx.coroutines | Language and runtime | Apache License 2.0 |
| Coil 2 | Image loading in UI | Apache License 2.0 |

## Plate detection model
No model weights are distributed with this source. If you train one with Ultralytics YOLO
(`tools/train_plate_detector.py`), the Ultralytics code and its base weights are
**AGPL-3.0** (or an Ultralytics Enterprise licence). Check that licence and your dataset's
licence (for example CC BY 4.0 on Roboflow Universe) before you distribute an APK that bundles the model.
