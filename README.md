# KeyS

A free Android keyboard (IME) with **QWERTY**, **3x4 (T9-style)**, and **Emoji** layouts. Supports **Pinyin Chinese input** and **handwriting recognition**. Resizable. No tracking. No paywall.

This repo contains two things:

| Path        | What it is                                                                        |
| ----------- | --------------------------------------------------------------------------------- |
| `test.html` | Self-contained browser demo. Open it in Chrome (desktop or Android) and test it.  |
| `android/`  | Full Android Studio project. Build it once and you get the real keyboard APK.     |

---

## 1. Try the HTML demo (no build needed)

Just open `test.html` in any modern browser. On Android Chrome it behaves like a real on-screen keyboard:

- Three modes: **QWERTY**, **3x4** (T9 multi-tap), **Emoji** (10 categories).
- Drag the small horizontal bar above the keyboard to **resize** it. Size is remembered.
- Tap `🌐` to cycle layouts, or use the `123` / `ABC` / `😀` keys.

> The HTML demo is purely for previewing the look/feel and tuning the layouts.
> It does **not** install as a system keyboard — that's what the Android build does.

---

## 2. Build the Android APK

The project lives in [`android/`](android/). The fastest way:

### Option A — Android Studio (Recommended, 1 click)

1. Install [Android Studio](https://developer.android.com/studio) (free).
2. `File → Open…` and select the `android/` folder.
3. Studio will download Gradle 8.4 + the Android SDK on first sync. Accept the prompts.
4. Connect your Android phone (USB debugging on) **or** start an emulator.
5. Press the green **Run** button (▶). The app installs as **KeyS**.

A debug APK is also written to:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

The root-level `KeyS.apk` is a copy of this file ready to sideload. You can copy it to your phone and install it directly (enable "Install from unknown sources").

### Option B — Command-line build

Requires the Android SDK (`ANDROID_HOME` set) and JDK 17. From `android/`:

```bash
# first time only: generate the Gradle wrapper jar
gradle wrapper --gradle-version 8.4

# build the debug APK
./gradlew assembleDebug          # macOS / Linux
gradlew.bat assembleDebug        # Windows
```

> ℹ️ This repo intentionally **does not ship `gradle-wrapper.jar`** (binary). Android Studio creates it automatically on first sync; or run `gradle wrapper` once if you have Gradle installed.

### Submodule note

The native Pinyin engine depends on `libpinyin` as a git submodule. After cloning, run:

```bash
git submodule update --init --recursive
```

---

## 3. Activate the keyboard on your phone

After installing the APK:

1. Open the **KeyS** app.
2. Tap **Open Input Method Settings** → enable **KeyS**.
3. Tap **Choose KeyS** → pick it as your active keyboard.
4. Open any app, focus a text field, type away.

Drag the bar at the very top of the keyboard up/down to resize it. The chosen size persists across reboots.

---

## Project layout

```
android/
├── build.gradle                                  ← root build script
├── settings.gradle
├── gradle.properties
├── gradlew, gradlew.bat                          ← wrapper launchers
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/keyboard101/
        │   ├── Keyboard101Service.java           ← InputMethodService (core IME)
        │   ├── KeyboardView.java                 ← custom view: QWERTY, 3x4, Emoji + resize
        │   ├── EmojiData.java                    ← emoji categories (9 categories)
        │   ├── PinyinEngine.java                 ← JNI bridge to native pinyin segmentation
        │   ├── PinyinDictionary.java             ← pinyin→character map with frequency ranking
        │   ├── UpdateChecker.java                ← self-hosted OTA update checker
        │   └── SettingsActivity.java             ← setup/launcher screen
        ├── cpp/
        │   ├── CMakeLists.txt                    ← builds libpinyin_jni.so (C++17)
        │   ├── pinyin_jni.cpp                    ← JNI: 428-syllable table, DP segmentation
        │   └── libpinyin/                        ← git submodule
        └── res/
            ├── xml/method.xml                    ← IME subtype config
            ├── xml/network_security_config.xml   ← HTTPS-only policy
            ├── xml/file_paths.xml                ← FileProvider paths for APK updates
            ├── layout/activity_settings.xml
            ├── drawable/ic_launcher.png
            └── values/{strings,colors,themes}.xml
```

---

## Features at a glance

- QWERTY layout with shift, symbols (`123`), period/comma quick keys
- 3x4 keypad with T9-style multi-tap (rapid-tap cycles a→b→c→2 etc.)
- Emoji panel with 10 categories: Smileys, People, Animals, Food, Activities, Travel, Objects, Symbols, Flags
- **Pinyin Chinese input** — native JNI engine (`libpinyin` submodule) with 428-syllable DP segmentation; Java fallback included
- **Handwriting recognition** — Google ML Kit Digital Ink Recognition (model downloaded on first use)
- Language identification via Google ML Kit
- Self-hosted **OTA update checker** — fetches a JSON manifest, downloads the APK, and hands it to the system installer via FileProvider
- Drag-to-resize keyboard height, persisted via SharedPreferences
- Dark / Light theme toggle
- `🌐` key cycles layouts; long-press opens the system IME picker
- Haptic feedback on key tap
- HTTPS-only network policy (no cleartext traffic)
- Works from **Android 5.0 (API 21)** up to **Android 14 (API 34)**
- Targets arm64-v8a, armeabi-v7a, x86_64

---

## Dependencies

| Library | Purpose |
| ------- | ------- |
| `androidx.appcompat:appcompat:1.6.1` | Backwards-compatible Activity/View support |
| `com.google.android.material:material:1.11.0` | Material Design 3 components |
| `com.google.mlkit:digital-ink-recognition:18.1.0` | On-device handwriting recognition |
| `com.google.mlkit:language-id:17.0.4` | Language identification |
| `io.github.biezhi:TinyPinyin:2.0.3.RELEASE` | Pinyin dictionary backing |
| `libpinyin` (git submodule, native) | Syllable segmentation engine |

---

## License

Free to use, modify, share. No warranty.
