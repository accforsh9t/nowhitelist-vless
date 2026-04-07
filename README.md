# nowhitelist-vless

Android VPN client plus VPS helper scripts for a VLESS-based setup with optional whitelist bypass routing.

## Android app

The `app/` module contains a Jetpack Compose Android client with:

- tunnel list and tunnel editor
- VLESS URI import
- VPN permission flow
- connection status, public IP and runtime log
- reconnect handling
- whitelist bypass toggle
- editable bypass domain list
- editable Telegram CIDR list for direct routing

## Build

```bash
./gradlew.bat assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Wi-Fi ADB debug

Use placeholders from the current Wireless debugging screen on the phone:

```bash
adb pair <PHONE_IP>:<PAIR_PORT>
adb connect <PHONE_IP>:<CONNECT_PORT>
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nowlhitelist.vpn/.MainActivity
adb logcat --regex nowhitelist
```

## Repository layout

- `app/` Android client
- `scripts/install-vps.sh` VPS install flow
- `scripts/make-client-config.sh` client config generation
- `scripts/sync-whitelist.sh` whitelist sync helper
- `.env.example` environment template

## Notes

- Keep `.env` and generated server/client secrets out of public locations.
- Telegram DC IP ranges can change. The app supports editable CIDR input instead of hardcoding stale ranges.
- The bundled domain bypass list includes generic defaults and can be edited inside the app.