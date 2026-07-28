# 🎉 Party Games

Multiplayer mini-games for your phone — play together on the same WiFi. No app install needed (web version), or download the APK.

## Games

| Game | Description |
|------|-------------|
| 🔒 **Pattern Lock** | Memorise a dot pattern, tap it back. Gets longer each round. |
| 🏀 **Catch the Drop** | Tap falling balls before they hit the bottom. 3 misses = out. |
| 🎨 **Whack-a-Color** | Only tap the target color. Wrong color = penalty. |
| 👆👆 **Double Tap** | Left side = sight, right side = sound. React fast! |
| ⚡ **Flash Count** | Screen flashes N times. How many did you see? |
| ⌨️🔥 **Typing Under Fire** | Type a sentence while your phone vibrates randomly. |
| 😱 **Last to Tap** | Red = WAIT. Green = TAP! Too early or too late = out. |

## How to Play

### Quick Start (Browser)
```bash
npm install
node server.js
```
Open `http://localhost:3456` on your phone. One person hosts, others join via QR code or room code.

### APK
Download the latest APK from [Releases](../../releases). Enter your server IP (the computer running `node server.js`), then host or join.

## How It Works

```
Host Phone ──┐
Player 1 ────┼── WebSocket ─── Node.js Server (laptop/RPi)
Player 2 ────┘                 port 3456
```

All phones connect to the same server via WebSocket. The host picks a game, everyone plays simultaneously, scores sync in real-time.

## Build APK

```bash
npm install
npx cap sync android
cd android && ./gradlew assembleDebug
# APK at android/app/build/outputs/apk/debug/app-debug.apk
```

Or let GitHub Actions build it on push.
