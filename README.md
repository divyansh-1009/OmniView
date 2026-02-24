# OmniView

On-device Android screen intelligence system that captures, embeds, and semantically retrieves your screen history — fully local and privacy-first.

---

## Features

### Screen Capture
OmniView uses Android's `MediaProjection` API to capture your screen in the background via a foreground service. When you tap **Start Screenshot Service**, the app requests the necessary media projection permission. Once granted, a persistent notification is shown (as required by Android) and the `ScreenshotService` starts running, holding an active `MediaProjection` instance ready to capture frames.

All capture happens entirely on-device — no screenshots are uploaded or shared externally.

## Requirements

- Android 7.0 (API 24) or higher
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` permissions (granted automatically at install)
- `POST_NOTIFICATIONS` permission (prompted at runtime on Android 13+)

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **MediaProjection API** for screen capture
- **Foreground Service** with notification channel for background operation

## Getting Started

1. Clone the repo and open in Android Studio.
2. Build and run on a device or emulator running Android 7.0+.
3. Tap **Start Screenshot Service** and accept the screen capture permission prompt.

---

> This project is under active development. Features like frame embedding and semantic retrieval are coming soon.
