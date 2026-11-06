<div align="center">

# Shizuku Nightzuku

An Android app that allows other apps to use system-level APIs that require ADB/root privileges.

**Shizuku Nightzuku** is a maintenance fork of [`thedjchi/Shizuku`](https://github.com/thedjchi/Shizuku), maintained by [**kerneldroid**](https://github.com/kerneldroid). It backports the native C++ supervisor, **NightDog** watchdog, **Android 17** hidden-API compatibility, and **Android 17 QPR3** wireless ADB fixes from [**Nightzuku**](https://github.com/kerneldroid/Nightzuku) while preserving `thedjchi`'s original UI and feature set.

[![Latest Release](https://img.shields.io/github/v/release/kerneldroid/Shizuku-Nightzuku?style=for-the-badge&color=3060bf&labelColor=204080&label=Latest%20Release)](https://github.com/kerneldroid/Shizuku-Nightzuku/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/kerneldroid/Shizuku-Nightzuku/total?style=for-the-badge&color=bf7830&labelColor=805020&label=Downloads)](https://github.com/kerneldroid/Shizuku-Nightzuku/releases)

</div>

## ⚠️ Disclaimer

This is a **fork** of Shizuku. The original project is [`thedjchi/Shizuku`](https://github.com/thedjchi/Shizuku); the upstream project is [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku). All credit for the original application, UI, and feature set belongs to their authors.

## ⬇️ Download

Get the latest signed APK from [GitHub Releases](https://github.com/kerneldroid/Shizuku-Nightzuku/releases/latest).

All versions are distributed through GitHub Releases and built by GitHub Actions from this repository.

## 🔧 What Is Fixed in This Fork (Ported from Nightzuku)

* **NightDog watchdog and native C++ supervisor (`nightdog.cpp`, `starter.cpp`, `NightDog`, `WatchdogService`):**
  * C++ `nightdog` process supervisor inside `libshizuku.so` with monotonic uptime tracking (60-second stable reset), automatic server respawn on unexpected exits or signals, `--replace=<pid>` handoff, UID `0`/`2000` verification against spoofed processes, and stopped/zombie (`T`/`Z`) state recovery.
  * Server-side `NightDog` daemon inside `shizuku_server` monitoring main-looper heartbeats (with main-thread stack trace dump on freeze) and `IBinder.linkToDeath` with exponential backoff (5–60 seconds, up to 10 attempts) on critical system services (`package`, `activity`, `user`, `appops`, `permissionmgr`).
  * Integrated with `thedjchi`'s `WatchdogService` through `NightDogClient` over Binder IPC, giving the native C++ supervisor priority to recover `shizuku_server` before falling back to a wireless ADB restart.
* **C++/JNI hardening (`starter.cpp`, `misc.cpp`, `selinux.cpp`, `adb_pairing.cpp`, `helper.cpp`):**
  * Fixed `__getcon` in `selinux.cpp` unconditionally returning `0` on read or allocation failure, and added `dlsym` null checks so fallback SELinux handlers are never overwritten with `nullptr`.
  * Fixed a file descriptor leak in `copyfileat` on `fstat` failure, an empty-string false positive in `is_num`, missing `O_CLOEXEC` flags, and a JNI string leak in `helper.cpp`.
  * Replaced stack VLAs in `adb_pairing.cpp` with heap buffers, added null and bounds checks, explicit little-endian 64-bit sequence nonces, and `OPENSSL_cleanse` key zeroing.
* **Android 17 compatibility (`Android17Compat`, `UsersCompat`, `InstalledPackagesCompat`, `ShizukuSystemApis`):**
  * Replaced the broken `Android17Compat` parameter order and `int` device-ID assumptions with Nightzuku's verified `IPermissionManager`/`IPackageManager` candidate resolver (`packageName` first, `permissionName` second, `"default:0"` persistent device ID, `checkUidPermission(uid, perm, 0)`, and an API <= 32 `int` flags fallback).
  * Added `UsersCompat` handling for Android 17 QPR1+'s single-boolean `IUserManager.getUsers(boolean excludeDying)` signature, so `NoSuchMethodError` no longer breaks multi-user discovery.
* **Android 17 QPR3 wireless ADB `WRITE_SECURE_SETTINGS`/`MANAGE_DEBUGGING` crash fix (`AdbSettings`):**
  * Android 17 QPR3 requires `MANAGE_DEBUGGING` (`signature|privileged`) in `SettingsProvider` when writing `adb_enabled`, `adb_wifi_enabled`, or `adb_allowed_connection_time`. Unguarded `Settings.Global.putInt` calls therefore crash with `SecurityException`.
  * `AdbSettings` is used across `StartWirelessAdbViewHolder`, `AdbDialogFragment`, `AdbStarter`, `AdbStartWorker`, and `ShizukuStateMachine`: it skips keys that already hold the desired value, catches `SecurityException` on direct writes, and falls back to `settings put global` through `shizuku_server`.
* **Additional under-the-hood fixes from Nightzuku:**
  * Thread-safe `AdbMdns` discovery state machine, a fixed shadowed `socket` leak in `AdbClient`, a fixed `portKey` collision in `AdbPairingService`, root-to-shell `shizuku.json` ownership handoff (`fchown`/`fchmod`) in `ShizukuConfigManager`, and tapjacking/label-sanitization hardening in `RequestPermissionActivity`.

## ✨ Features (from `thedjchi/Shizuku`)

* **More robust "start on boot":** waits for a Wi-Fi connection before starting the Shizuku service
* **TCP mode:** after Shizuku starts over Wi-Fi once, it can be stopped and restarted without a Wi-Fi connection
* **Watchdog service (powered by NightDog):** supervises and restarts Shizuku if it stops or hangs unexpectedly
* **Start/stop intents:** toggle Shizuku on demand from automation apps such as Tasker, MacroDroid, or Automate
* **[BETA] Stealth mode:** hide Shizuku from apps that do not work when Shizuku is installed
* **[BETA] In-app updates:** optionally check for new releases and download/install them from GitHub
* **Android/Google TV and VR headset support:** D-Pad-compatible UI, support for all TVs (including Android 14+ TVs that require pairing), and a toggleable multi-window pairing dialog for VR headsets
* **MediaTek support:** fixes a critical bug in upstream v13.6.0 that prevented Shizuku from working on MediaTek devices

## 📝 User Guide

Read the upstream [wiki](https://github.com/thedjchi/Shizuku/wiki) for setup, usage, and troubleshooting.

## ☑️ Requirements

**Minimum version: Android 7+**

* **Root mode:** requires a rooted device
* **Wireless debugging mode:** works on Android 11+ and all Android TVs
* **PC mode:** works on all devices
* **Start on boot:** available only in wireless debugging or root mode

## 🔒 Privacy

Shizuku takes user privacy seriously:

* No tracking or analytics
* No telemetry
* No proprietary libraries
* No Google Play Services
* Open-source codebase
* Internet access is used only for wireless debugging connections and GitHub update checks
* Only required permissions are declared

### Permissions

* **INTERNET:** required for wireless debugging and GitHub update checks
* **ACCESS_NETWORK_STATE:** determines when Wi-Fi is available for background startup
* **POST_NOTIFICATIONS:** required for pairing notifications and alerts
* **RECEIVE_BOOT_COMPLETED:** required for start on boot
* **FOREGROUND_SERVICE:** keeps the watchdog alive
* **REQUEST_IGNORE_BATTERY_OPTIMIZATIONS:** prevents the start-on-boot and watchdog services from being killed
* **WRITE_SECURE_SETTINGS:** toggles USB and wireless debugging in the background
* **REQUEST_DELETE_PACKAGES:** requests uninstall of Shizuku or its stub in stealth mode
* **REQUEST_INSTALL_PACKAGES:** requests install of app updates and the stealth-mode stub

## 🌎 Translations

Upstream translations are managed through the [Crowdin project](https://crowdin.com/project/shizuku).

## 📱 Developer Guide

### API and demo project

The API guide and a demo project are available in the [Shizuku-API](https://github.com/thedjchi/Shizuku-API) repository.

### Notes

1. Shizuku has different permissions in root and ADB mode. Permissions granted to ADB are listed [here](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/Shell/AndroidManifest.xml). If your app requires root permission, use `ShizukuService#getUid` to check whether Shizuku runs as root or ADB, or `ShizukuService#checkPermission` to check whether the server has sufficient permissions.
2. On Android 8 or lower, if you need to use Shizuku in a service or broadcast receiver that might not be started by an activity, trigger the binder by starting a transparent activity.
3. Prefer `ShizukuBinderWrapper` over `transactRemote` when possible, because API calls can change across Android versions.

### Building

* Clone with `git clone --recurse-submodules`
* Run `./gradlew :manager:assembleDebug` or `./gradlew :manager:assembleRelease`
* Release builds read signing values from `signing.properties` (see `signing.gradle`)

The `:manager:assembleDebug` task generates a debuggable server. You can attach a debugger to `shizuku_server` to debug the server. In Android Studio, enable `Run/Debug configurations > Always install with package manager` so the server uses the latest code.

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b branch-name`)
3. Make your changes
4. Commit your changes (`git commit -m 'Commit message'`)
5. Push the branch (`git push origin branch-name`)
6. Open a pull request

## 📃 License

All code files in this project are licensed under the [Apache 2.0 License](LICENSE).
