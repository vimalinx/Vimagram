## Vimagram (Android)

English | [中文](README.md)

Android client for the Vimalinx Server channel. Use it to register/login,
generate host tokens, and chat.

Highlights:
- Connects directly to Vimalinx Server (not the Gateway).
- Host tokens live in **Account** for easy recovery.
- Language toggle (System/Chinese/English).

## Open in Android Studio
- Open the folder `app`.

## Build / Run

```bash
cd app
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:testDebugUnitTest
```

For a release build:

```bash
./gradlew :app:assembleRelease
```

Latest APKs are published on GitHub Releases: <https://github.com/vimalinx/vimalinx-suite-core/releases>

`gradlew` auto-detects the Android SDK at `~/Library/Android/sdk` (macOS default) if
`ANDROID_SDK_ROOT` / `ANDROID_HOME` are unset.

## Connect to Vimalinx Server

1) Start the server (see `server/README.md`).
2) In Vimagram, register or log in with the server URL and your user ID.
3) Generate a host token from **Account** and copy it.
4) In the gateway CLI, install/configure the Vimalinx Server plugin
   (see `plugin/README.md`) and paste the token.

## Notes

- Debug builds can be installed directly with `:app:installDebug`.
- If the device prompts for install permissions, approve it to finish the install.
