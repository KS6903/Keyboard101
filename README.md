# Keyboard101

A free Android keyboard (IME) with **QWERTY**, **3x4 (T9-style)** and **Emoji** layouts. Resizable. No tracking. No paywall.

This repo contains two things:

| Path        | What it is                                                                       |
| ----------- | -------------------------------------------------------------------------------- |
| `test.html` | Self-contained browser demo. Open it in Chrome (desktop or Android) and test it. |
| `android/`  | Full Android Studio project. Build it once and you get the real keyboard APK.    |

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
5. Press the green **Run** button (▶). The app installs as **Keyboard101**.

A debug APK is also written to:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

You can copy that `.apk` to your phone and install it directly (enable "install from unknown sources").

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

---

## 3. Activate the keyboard on your phone

After installing the APK:

1. Open the **Keyboard101** app.
2. Tap **Open Input Method Settings** → enable **Keyboard101**.
3. Tap **Choose Keyboard101** → pick it as your active keyboard.
4. Open any app, focus a text field, type away.

Drag the bar at the very top of the keyboard up/down to resize it. The chosen size persists across reboots.

---

## Project layout

```
android/
├── build.gradle                                  ← root build script
├── settings.gradle
├── gradle/wrapper/gradle-wrapper.properties      ← Gradle 8.4 pinned
├── gradlew, gradlew.bat                          ← wrapper launchers
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/keyboard101/
        │   ├── Keyboard101Service.java           ← InputMethodService
        │   ├── KeyboardView.java                 ← custom view, all 3 layouts + resize
        │   ├── EmojiData.java                    ← emoji categories
        │   └── SettingsActivity.java             ← setup screen
        └── res/
            ├── xml/method.xml                    ← IME subtype config
            ├── layout/activity_settings.xml
            ├── drawable/ic_launcher.xml
            └── values/{strings,colors,themes}.xml
```

## Features at a glance

- ✅ QWERTY layout with shift, symbols (`123`), period/comma quick keys
- ✅ 3x4 keypad with T9-style multi-tap (rapid-tap cycles a→b→c→2 etc.)
- ✅ Emoji panel with 10 categories: Smileys, People, Animals, Food, Activities, Travel, Objects, Symbols, Flags
- ✅ Drag-to-resize, height persisted via SharedPreferences
- ✅ `🌐` key cycles layouts; long path via `Switch IME` system picker also wired up
- ✅ Haptic feedback on key tap
- ✅ Works from Android 5.0 (API 21) up to Android 14 (API 34)

## License

Free to use, modify, share. No warranty.
