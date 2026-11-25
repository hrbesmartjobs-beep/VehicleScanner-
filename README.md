# Vehicle Scanner — offline multi-plate checker (Android)

Take one photo of many vehicles. The app finds each number plate, crops it and reads it
with on-device OCR. It then checks every number against your local vehicle list and sorts
the results into:

- **GREEN – MATCH FOUND**
- **RED – NOT FOUND**
- **YELLOW – REVIEW REQUIRED** (unreadable, low confidence, non-standard, or one character away from a saved number)

Scanning, OCR, matching and the database all run on the phone. The app requests **no
permissions**, and the INTERNET permission is removed from the manifest.

---

## 1. Build status: read this first

| Item | Status |
|---|---|
| Kotlin source, Compose UI, Room DB, import/export, backup | Written in full |
| Core logic (plate normaliser, matcher, NMS/tiling, YOLO decoder, CSV/XLSX read+write, import analysis) | **25 JVM unit tests run and passing** (Kotlin 2.0.21) |
| Android build (`assembleDebug`) | **Not run yet.** The machine that wrote this had no Android SDK and no internet. Build with step 2 below. |
| APK install and real-photo scanning (TEST 1–5, 8, 9) | **Not run yet.** Needs a phone. |
| YOLO plate model file | **Not included.** See step 3. The app works without it through the built-in OpenCV detector. |

If the first build shows a compile error, paste it back and it can be fixed. The Android
code has been reviewed by hand against the library APIs, but it has never been compiled.

---

## 2. Get the APK

### Option A: GitHub Actions (no PC setup, works from a phone)
1. Create a new GitHub repository and upload the *contents* of this folder, including the hidden `.github` folder.
2. Open the **Actions** tab. The **Build APK** workflow runs on every push (or press *Run workflow*).
3. After about 10 minutes, open the finished run and download the **VehicleScanner-apk** artifact.
   It contains `app-debug.apk` and `app-release.apk` (debug-signed, installs directly).
4. On the phone, allow "Install unknown apps" for your browser or file manager, then open the APK.

### Option B: Android Studio
1. Install Android Studio (Ladybug 2024.2 or newer) with SDK 35.
2. Use *File → Open* to open this folder and let Gradle sync. The first sync downloads dependencies, which needs internet once.
3. *Build → Build App Bundle(s)/APK(s) → Build APK(s)*. The output is in `app/build/outputs/apk/debug/`.
4. Command line alternative: `./gradlew assembleDebug testDebugUnitTest`

Toolchain: AGP 8.7.3 · Gradle 8.11.1 · Kotlin 2.0.21 · JDK 17 · minSdk 26 · targetSdk 35.

---

## 3. Plate detection: how it works

Detection always runs **per plate**. Full-image OCR is never used as a substitute.

1. **YOLO (TensorFlow Lite), the best option.** This is used when a model is present at
   `app/src/main/assets/models/plate_detector.tflite` (bundled at build time) or has been imported in
   **Settings → Plate detection model → Import .tflite** (no rebuild needed). The detector runs on the
   full photo and on overlapping 1280-px tiles, so small, distant plates in crowded parking photos are
   still found. Boxes are merged with NMS and a containment check, which removes duplicates.
   Supported model types are YOLOv5, v8, v11 and NMS-free exports, in float32, float16 or int8.
2. **OpenCV classical detector, always available.** It uses black-hat/top-hat morphology, a
   horizontal gradient, closing and contour geometry filters, at two scales and both polarities
   (white/yellow plates and black/green plates). Each candidate is checked by OCR before it is kept.

**No trained model file is shipped.** No internet was available to download or train one, and the
spec forbids fake substitutes. To get a model, run `tools/train_plate_detector.py` on a PC with a GPU
or on Google Colab, using an Indian number-plate dataset in YOLO format. Then drop the `.tflite` into
the app. The script header has a licence note: Ultralytics is AGPL-3.0.

Each crop then goes through **enhancement**: upscaling, deskew, CLAHE contrast, sharpening and
adaptive threshold. Three variants are read with **ML Kit Text Recognition (bundled model, offline)**,
and the best reading is chosen. Agreement between variants raises the confidence. Two-line plates
(bikes, trucks) are reassembled top to bottom.

## 4. Number rules (India)

- Cleaning: uppercase, then remove spaces, hyphens and dots. `KL-08-AB-1234`, `KL 08 AB 1234` and `kl08ab1234` all become `KL08AB1234`.
- Supported formats: `SS DD L{0,3} NNNN` (with a valid state/UT code), Delhi `DL3CAB1234`, and Bharat series `22BH1234AA`.
- OCR confusion fixes (O↔0, I↔1, S↔5, B↔8, Z↔2, G↔6…) are applied **only at positions where the format requires a letter or a digit**.
  Every fix lowers the confidence by 8 points and is shown on the result card.
- The "IND" hologram text and border junk are removed.
- A number is **never** auto-matched below the confidence threshold (default 75%, set in Settings). It goes to REVIEW.
- If a reading has no exact match but is one character away from a saved number, it goes to REVIEW with a "Use KL 08 AB 1234" button. It never goes silently to NOT FOUND.
- Numbers in your database are cleaned but never auto-corrected.

## 5. Features
- Dashboard: SCAN PHOTO, TAKE PHOTO, IMPORT EXCEL, VEHICLE DATABASE, SCAN HISTORY and SETTINGS, plus vehicle and scan counts.
- Background scanning with a progress screen and cancel. The scan keeps running if you leave the screen.
- Results: four count tiles (tap to filter) and filter chips. Each card shows the plate crop, the number, a confidence bar and the raw OCR text. Actions: edit, accept suggestion, "reading is correct" and add to database. Results can be exported to CSV or XLSX, with colour-coded status cells in XLSX. The original photo opens with coloured boxes and pinch-zoom. You can re-check a scan against the database after importing more numbers.
- Database: add, edit, delete, search and delete all. Duplicates are blocked by a unique index. Exports to CSV/XLSX.
- Import: `.xlsx` or `.csv` (with `,` `;` tab or `|` delimiters). Choose the worksheet. The number column is detected automatically and can be picked by hand. A summary is shown before importing: total rows, to import, duplicates in file, already in DB, invalid (listed by row) and empty. Old `.xls` files get a clear message asking you to re-save as .xlsx.
- History: every scan is saved with its photo and crops.
- Settings: light, dark or system theme. Confidence and detector thresholds, tiled detection, and combining YOLO with OpenCV. Model import. Backup to a ZIP (vehicles, history and optionally photos) and restore it (merge or replace). Delete all local data.

## 6. Test plan (spec §12)

| # | Scenario | Status |
|---|---|---|
| 1 | One matching vehicle | Not run (needs device). Matching logic is unit-tested (`exactMatchHighConfidence`). |
| 2 | Multiple matching vehicles | Not run (needs device) |
| 3 | Matching and non-matching mix | Not run (needs device). Logic is unit-tested (`notFound`, `nearMissGoesToReview`). |
| 4 | 20+ vehicles | Not run (needs device). Tiling and NMS are unit-tested. |
| 5 | Blurry or angled plates | Not run (needs device) |
| 6 | Duplicate numbers in Excel | **Passed** (unit test `importAnalysisCountsDuplicatesInvalidAndExisting`) |
| 7 | Large Excel import | **Passed** at the logic level: 20,000-row analysis in `largeImport` plus an XLSX write/read round trip. Room insert on a device was not run. |
| 8 | Restart and offline operation | Not run (needs device). Data is stored in Room and app files, and there is no network permission. |
| 9 | Wrong OCR and manual correction | Normaliser and low-confidence rules unit-tested. UI flow not run. |
| 10 | Export scan results | XLSX/CSV writers unit-tested. Device export not run. |

Run the unit tests with `./gradlew testDebugUnitTest`. The GitHub workflow runs them on every push.

## 7. Project layout
```
app/src/main/java/com/vehiclescanner/
  core/    pure Kotlin, unit-tested: PlateNormalizer, VehicleMatcher, PlateTextAssembler,
           Geometry (Box/NMS/Tiler/Letterbox), YoloDecoder, SpreadsheetIO (CSV + XLSX), ImportAnalyzer
  vision/  YoloPlateDetector (TFLite), OpenCvPlateDetector, PlateEnhancer, PlateOcr (ML Kit), ImageUtils
  scan/    ScanPipeline, ScanManager, ModelManager
  data/    Room database, repositories, settings, export, backup/restore
  ui/      Compose screens + theme
tools/     train_plate_detector.py (YOLO → TFLite)
.github/workflows/build-apk.yml
```

## 8. Known limits
- Without a YOLO model, the OpenCV detector handles clear, roughly frontal plates well. It is weaker
  on very small, heavily angled or partly hidden plates. Install a trained model for crowded parking lots.
- ML Kit reads Latin characters only. Plates in other scripts go to REVIEW.
- Very damaged or dirty plates will land in REVIEW by design. Correct them on the result card.
