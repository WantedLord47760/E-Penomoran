# JetGPT Spaceman AutoCapture 1.6

Android application for locally reading visible CALO11 multiplier history with Android screen capture permission and on-device ML Kit OCR.

## Features
- Android MediaProjection screen capture and ML Kit OCR.
- Imports visible history from INFO → STATS → HASIL TERAKHIR.
- Conservatively detects new leading results when old/new lists overlap.
- Local-only storage (up to 2,000 records).
- Dashboard: count, average, median, and historical frequency thresholds.
- Manual multiplier entry fallback.
- Does not transmit screen images, account data, tokens, or cookies.

## Use
1. Install the APK and open JetGPT Spaceman.
2. Tap MULAI PANTAU LAYAR and approve Android's screen capture dialog.
3. Switch to CALO11 in the browser.
4. Open INFO → STATS → HASIL TERAKHIR and keep the panel visible.
5. Return to JetGPT to review captured data and statistics.
6. Tap HENTIKAN to stop capture.

## Limits
OCR only reads visible text. Blurred text, animations, layout changes, or protected content can cause missed reads. If the game blanks screen capture, OCR cannot read it. New-round matching is conservative and will skip uncertain changes. Historical data cannot reveal the next multiplier with certainty.

## Build
GitHub Actions builds a debug APK on pushes to branch `jetgpt-spaceman-apk` and uploads artifact `JetGPT-Spaceman-debug-apk`.
