# PopSubs

## Description

PopSubs is a simple Android application developed in Kotlin to load local WebVTT (`.vtt`) **and SubRip (`.srt`)** subtitle files and display the subtitle cues synchronized with a simulated playback timer. This app includes playback controls, seeking, screen awake functionality during playback, and a floating overlay window feature.

## Features

* **Load Local Subtitle Files:** Select `.vtt` **or `.srt`** files using the Android system file picker (`ACTION_OPEN_DOCUMENT`). Includes robust filename retrieval and extension checking.
* **VTT Parsing:** Parses `WEBVTT` files to extract cue timings (start/end times) and subtitle text content. Handles multi-line cues and potential Byte Order Mark (BOM). Includes basic format validation.
* **SRT Parsing:** Parses `.srt` files, extracting sequence numbers, timestamps (handling both `.` and `,` for milliseconds separator), and multi-line cue text. Includes basic format validation.
* **Simulated Playback:**
    * Displays elapsed time in `MM:SS.ms` format.
    * Shows the currently active subtitle cue based on the elapsed time.
    * Provides Play/Pause (`MaterialButton` with dynamic icon) and Reset (`MaterialButton` with icon) controls.
* **Seeking:** Includes a `Material Slider` allowing the user to jump to specific points in the subtitle timeline within the main application.
* **Keep Screen Awake:** Prevents the device screen from turning off automatically *only* during active subtitle playback.
* **Floating Overlay Window:**
    * Displays the current subtitle text in a system overlay window that can float above other apps.
    * Uses an Android `Service` (`OverlayService`) and `WindowManager`.
    * Handles the `SYSTEM_ALERT_WINDOW` (Draw over other apps) permission request flow.
    * Uses `LocalBroadcastManager` for efficient Activity-Service communication.
* **Overlay Toggle:** A button ("Toggle Overlay Visibility") in the main app allows the user to manually hide or show the floating overlay window. The service manages its own visibility based on received broadcasts.
* **Material Theming:** Basic UI theming implemented using Material Components, with primary colors adapted from the app icon.

## Setup & Build

There are two ways to get the app:

1.  **Build from Source:**
    * Clone this repository.
    * Open the project in a recent version of Android Studio (e.g., Iguana or later).
    * Ensure the Material Components dependency (`implementation 'com.google.android.material:material:...'`) is present in `app/build.gradle` or `app/build.gradle.kts` and sync the project with Gradle files.
    * Build the project (`Build` > `Make Project`).
    * Run the app on an Android device or emulator (API 23+ recommended due to overlay permission handling).

2.  **Download Pre-built APK:**
    * Alternatively, check the **[Releases section](releases)** of this repository for a pre-built `app-release.apk` file that you can download and install directly onto your Android device.

## Usage

1.  Launch PopSubs.
2.  Click "Select VTT / SRT File" and use the file picker to choose a valid `.vtt` **or `.srt`** file.
3.  Once loaded successfully, the playback controls, slider, and "Toggle Overlay Visibility" button will be enabled.
4.  Use the Play/Pause/Reset buttons to control the simulated playback. The screen will stay on while playing.
5.  Use the Slider to seek to different times.
6.  Click "Toggle Overlay Visibility":
    * Grant the "Draw over other apps" permission via system settings if prompted the first time.

## Known Issues / Limitations

* Subtitle parsers are relatively basic; may not support all advanced VTT/SRT features (e.g., complex inline styling, positioning tags).
* Playback is simulated using a timer; it is **not** synchronized with any actual video or audio player.
* Error handling for malformed subtitle files is basic.
* Seeking controls are only available within the main app UI.
* Overlay cannot be repositioned.
