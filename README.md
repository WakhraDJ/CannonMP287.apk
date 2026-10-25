# Canon Scan (Android) — PIXMA MP280/MP287 over USB OTG

Phase 1: **scanning**. Phase 2: **printing** (see PRINTING.md). Targets Android 12 (API 31) to
Android 14 (API 34), tablets included. Uses the SANE `pixma` backend core (version 1.4.0 / pixma 0.28.6)
for the scanner protocol, with SANE's libusb layer replaced by Android's USB Host API.

## Build
1. Install **Android Studio Koala (2024.1.1) or newer**.
2. In SDK Manager install: Android SDK Platform 34, **NDK (Side by side)**, **CMake 3.22.1**.
3. File ▸ Open ▸ this folder, let Gradle sync.
4. Build ▸ Build APK(s), copy `app/build/outputs/apk/debug/app-debug.apk` to the tablet and install it
   (allow "install unknown apps"). Sideloading is easiest because the tablet's USB port will be used by the printer.

### No Android Studio? Let GitHub build the APK
1. Create a new **private** GitHub repository and upload every file of this folder (including the hidden `.github` folder).
2. Open the repo's **Actions** tab ▸ *Build APK* ▸ **Run workflow** (it also runs on every push).
3. When it finishes, open the run and download the **CanonScan-debug-apk** artifact (a zip containing `app-debug.apk`).
4. Unzip, copy the APK to the tablet, install it. If the build fails, send me the red error text.

## Use
1. Fix any error light on the printer first (E05 = cartridges missing/not recognised; scanning is blocked until it clears).
2. Plug the printer into the tablet with a USB OTG adapter + the printer's USB cable, power the printer on.
3. Open the app ▸ **Connect printer** ▸ allow USB access ▸ **Scan** (75/150/300 dpi, colour or grey).
4. Save as JPEG (Pictures/CanonScan) or PDF (Downloads/CanonScan).
5. If anything fails: tick *Verbose USB log*, retry, then **Save diagnostics file** and send me
   `Downloads/CanonScan/canonscan_diagnostics.txt`.

## Status — read this
| Part | State |
|---|---|
| Native scan core compiles (host gcc) | Verified |
| JNI bridge loads, rejects unknown USB IDs, recognises 04A9:1746 as "Canon PIXMA MP280", sends first protocol command | Verified in a JVM with a fake transport |
| Kotlin app, Gradle files, manifest | **Written but never compiled** (no Android SDK available when this was made). Expect a few build errors to fix on first sync; send them to me. |
| Real scan on the real printer | **Not tested** (no hardware access). |
| USB ID of your MP287 | **Unconfirmed.** Windows names it "MP280 series"; the app shows the real ID on Connect. |
| 600 dpi | Not exposed (SANE notes 1200 dpi does not work for MP280; 600 would need a strip-based PDF writer to avoid running out of memory). |
| Printing | Not implemented — see PRINTING.md |

## Layout
- `app/src/main/cpp/pixma/` — SANE pixma core (`pixma_common.c` patched to list only the mp150 sub-driver).
- `app/src/main/cpp/io/pixma_io_android.c` — USB transport replacement (calls Kotlin `UsbTransport`).
- `app/src/main/cpp/jni/scan_jni.c` — JNI scan entry point, writes raw rows to a cache file.
- `app/src/main/java/com/canonscan/` — USB, UI, image saving.

## Licence
The SANE pixma code is **GPL-2.0-or-later** (with SANE's linking exception for SANE libraries). This project
links it, so distribute the whole app under GPL-2.0-or-later and keep the source available. See COPYING.
Modified SANE files: `pixma_common.c` (device list trimmed). New files derived from SANE: `pixma_io_android.c`.
