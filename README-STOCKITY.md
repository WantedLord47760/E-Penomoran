# JETGPT STOCKITY AI
Android prototype for local visual scanning of a Stockity candlestick chart.

## Use
1. Install the APK.
2. Open the app and tap MULAI SCAN LAYAR.
3. Approve Android screen sharing.
4. Switch to Stockity and show the Crypto IDX candlestick chart.
5. The app scans visible chart pixels locally and sends a notification when its visual rules align for BUY or SELL. Otherwise it reports WAIT.

## Limits
- This is visual computer-vision analysis, not an official Stockity market-data feed.
- It does not place trades, click buttons, or access account credentials.
- Detection depends on chart theme, candle colors, zoom and screen layout.
- The score is rule alignment, not a win probability. Signals are experimental; test only on a demo account.
- No screenshots or market data are uploaded by this prototype.
