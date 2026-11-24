# Pothole detection – developer notes

On-device pothole detection on the dashcam's live RTSP stream (boAt Hive F1 Pro), using a
fine-tuned YOLO11n model running with LiteRT (TensorFlow Lite) on the phone.

## Pipeline

```
RtspH264Client ──► live view (SurfaceView, unchanged)
      │ access units (+ SPS/PPS)
      ▼
AnalysisFrameDecoder ── 2nd H.264 decoder, CPU-readable frames, road strip → Bitmap
      │   (fallback: PixelCopy of the on-screen video if this phone's decoder gives no frames)
      ▼
FrameDetectionLoop ─► FrameQualityEvaluator ─► TflitePotholeDetector ─► PotholeConfirmer ─► PotholeEventLog
   ~2.3–3.5 checks/s     skip dark/blown-out      YOLO11n .tflite          2 of last 3 checks    GPS + snapshot + CSV,
                         frames                   (FP32 or INT8)                                 de-duplicated
      ▼
DetectionOverlay (red boxes) + read-out under the "AI pothole detection" switch
```

## Files

| File | Purpose |
|---|---|
| `detect/TflitePotholeDetector.kt` | Loads `assets/pothole_*.tflite`, letterbox 640×640, NCHW/NHWC input, decodes YOLO output, NMS. `debug = true` logs best score per frame and saves model-input images. |
| `detect/FrameDetectionLoop.kt` | Frame capture → quality → model → confirmation → log; logs a `SESSION SUMMARY` (checks/s, ms per check, potholes) when detection stops. |
| `detect/FrameQuality.kt` | Sharpness/brightness score (ported from pothole-reporter). |
| `detect/PotholeConfirmer.kt` | Multi-frame confirmation (2 of 3). |
| `detect/PotholeEventLog.kt` | Pothole log with GPS + snapshot, de-duplication (12 m / 5 m / 4 s). Saved to `Android/data/com.boat.dashcamcompanion/files/Documents/potholes/<session>/`. |
| `detect/Detection.kt` | `Detection` box + `RoadBand` (area of the frame analysed). |
| `stream/AnalysisFrameDecoder.kt` | Background decoding for analysis. Default: MediaCodec output buffers (works on all phones). Optional ImageReader mode (`preferImageReader = true`) with crash protection. |
| `stream/RtspH264Client.kt` | Added `codecConfigListener` (SPS/PPS for the 2nd decoder) and RTSP keep-alive (`GET_PARAMETER` every 20 s). |
| `ui/LiveViewScreen.kt` | Wires the pipeline into the live view; `USE_DECODER_FRAMES` switch. |
| `ui/VideoTestScreen.kt` | Bench test: runs the pipeline on a video file from the phone (FP32/INT8 switch, confirmation and debug switches). |
| `ui/HomeScreen.kt`, `MainActivity.kt` | Entries for the video-file test and the PC RTSP stream test. |

## Testing without a road

- **Video-file test** (app home screen) – any dashcam video stored on the phone.
- **PC RTSP stream** (app home screen) – the PC streams a video like the dashcam:
  `mediamtx` + `ffmpeg -re -stream_loop -1 -i test_clip.mp4 -an -vf scale=960:544 -r 25 -c:v libx264 -preset veryfast -tune zerolatency -bf 0 -g 25 -b:v 2500k -f rtsp -rtsp_transport tcp rtsp://localhost:8554/dashcam`,
  then connect the app to `rtsp://<PC-IP>:8554/dashcam`.
- Logcat filter: `package:mine tag~:FrameDetectionLoop|TflitePotholeDetector|AnalysisFrameDecoder|PotholeEventLog`

## Measured (vivo V2334)

| | FP32 | INT8 |
|---|---|---|
| ms per check (median) | 397 | 246 |
| checks per second | 2.34 | 3.55 |

Same test frame: PC `best.pt` 0.818, PC `.tflite` 0.817, phone 0.817 (full frame), 0.751 (road strip).

## Known limitations

- Model trained on one 24-minute drive (486 labelled frames, city roads, 1080p SD-card video).
  Live stream is 960×544 with a text overlay; recall is lower on unpaved, water-filled and
  distant potholes. Retraining with live-stream recordings and RDD2022 India is in progress.
- Filming a screen or projector with the dashcam is not a valid accuracy test (tint, blur,
  small image); use the video-file or PC-stream tests, or a road test.
- Device testing so far: vivo V2334, LG LM-G850, one MediaTek phone (ImageReader crash, fixed by
  the default decoder-buffer mode – retest pending).

## Credits and licences

- Parts adapted from **pothole-reporter** by Gaurav Sen (https://github.com/coding-parrot/pothole-reporter),
  MIT License, © 2026 Gaurav Sen – frame decoding approach, `FrameQualityEvaluator`, burst
  confirmation, de-duplication rules. Credited in each adapted file.
- **Ultralytics YOLO** models are AGPL-3.0 unless an Enterprise licence is obtained – licensing for
  commercial use of the fine-tuned model must be confirmed.
- **RDD2022** dataset (if used for training): CC BY / CC BY-SA 4.0 – attribution required.
