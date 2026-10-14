## VisionTrack

A real-time object detection, tracking, and video recording Android application powered by **Google MediaPipe Tasks Vision** (`efficientdet_lite0.tflite`) running on local hardware.

### Key Features 

##### **Real-Time Camera Detection & Tracking** (`LiveTrack`):

- **Local MediaPipe Vision Integration**: Hardware-accelerated inference running on camera frames via CameraX `ImageAnalysis`.
- **Continuous Object Tracking**: Multi-object tracker maintaining unique tracking IDs (`#1`, `#2`, ...), IoU-based cross-frame matching, trajectory smoothing, and movement velocity metrics (`px/s`).
- **Prominent Object Identification**: Real-time prominence scoring combining bounding box area, classification confidence, and center proximity.
- **Cyber-Optics HUD Overlay**: Golden animated corner target brackets, pulsing center reticle, telemetry badges, and glowing trajectory breadcrumb trails for the prominent object.

**Interactive Tap-to-Lock**: Tap on any detected object in the live camera view to lock it as the prominent target.
**Camera Controls**: Front/rear lens switching, torch/flashlight toggle, and real-time settings (confidence threshold slider and GPU/CPU delegation).

##### **Prominent Object Video Recording**:

- **Burned-In Tracking Process**: Records MP4 video (MediaCodec AVC/H.264 + `MediaMuxer`) with the prominent object's targeting reticle, telemetry HUD, trajectory breadcrumb trail, timestamps, and active REC indicator permanently rendered onto the video frames.

- **Gallery & Storage Integration**: Saved tracking recordings are automatically registered with Android `MediaStore` into the device's Movies directory for access across all apps.

##### **Static Image Object Detection** (`Gallery`):

- **Zero-Permission Photo Picker**: Pick any image from device storage (`ActivityResultContracts.PickVisualMedia`).

- **Synthetic Test Scenes**: Includes quick-load sample scenes *(e.g. Desk and Living Room*) to immediately test static inference.

- **Interactive Object Inspector**: Renders bounding boxes and a detailed card list with confidence percentages and normalized dimensions. Tapping an item highlights it on the canvas.

- **Annotated Image Export**: Save and share detection-annotated photos via Android's native Share Sheet.

##### **Saved Tracking Video Library** (`Videos`):

- In-app playback with a video player and looping controls.
- Video metadata (duration, file size, timestamp).
- Video sharing via Android `FileProvider` and deletion management.

---

**The application was created entirely by Gemini 3.8 Flash in Google AI Studio.**
