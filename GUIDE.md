# OpenVPN Integration Guide — `projectmata/mobile-openvpn`

This is the end-to-end install guide for getting OpenVPN actually tunneling on a NativePHP-Mobile app, reverse-engineered from the working setup in **EliteScanner_mobile**.

> **2026-10-08 package update:** The modified package now bundles the Android engine AAR and its source, and installs its Gradle wiring through a NativePHP build hook. Use [README.md](README.md) and `OpenVpn.ConnectProfile(ovpnText)` for current installation/usage. The manual engine-module steps below describe the earlier EliteScanner integration and are retained as historical implementation context; do not apply them alongside the bundled AAR. iOS reports unsupported.

> ⚠ **GPL-v2 warning.** ics-openvpn is GPL-v2. Linking it into your app makes the whole app GPL-v2 as well. Read [Licensing caveats](#licensing-caveats) before shipping commercially.

---

## 1. The architecture in one picture

```
┌───────────────────────────────────────────────────────────────────────┐
│ Laravel app                                                            │
│ ─────────                                                              │
│   PHP   →  Projectmata\MobileOpenVpn\Facades\OpenVpn                   │
│            └── OpenVpnManager::callNative('OpenVpn.Connect', […])      │
│                                                                        │
│   Vue/JS →  resources/js/composables/useVpn.js                         │
│            └── packages/.../resources/js/index.js                      │
│                └── fetch POST /_native/api/call?method=OpenVpn.Connect │
└───────────────────────────────────────────────────────────────────────┘
                                ↓ JSON-RPC over the NativePHP bridge
┌───────────────────────────────────────────────────────────────────────┐
│ Android app  (nativephp/android/app)                                   │
│ ─────────                                                              │
│   PluginBridgeFunctionRegistration.kt                                  │
│     registry.register("OpenVpn.Connect", OpenVpnPlugin.Connect(act))   │
│                                                                        │
│   com.projectmata.mobileopenvpn.OpenVpnPlugin                          │
│     ├── parses .ovpn (de.blinkt.openvpn.core.ConfigParser)             │
│     ├── builds VpnProfile + saves via ProfileManager                   │
│     ├── starts service with VPNLaunchHelper.startOpenVpn()             │
│     └── streams state/log/traffic to a ring buffer (GetEvents)         │
└───────────────────────────────────────────────────────────────────────┘
                                ↓ depends on (Gradle :openvpn:main)
┌───────────────────────────────────────────────────────────────────────┐
│ ics-openvpn  (nativephp/android/openvpn/)                              │
│   Provides de.blinkt.openvpn.* runtime — OpenVPNService,               │
│   VPNLaunchHelper, VpnStatus, IOpenVPNServiceInternal,                 │
│   IServiceStatus, IStatusCallbacks.                                    │
│   Source-included via `git clone` — NOT a Maven artifact.              │
└───────────────────────────────────────────────────────────────────────┘
```

There are **five wiring points** to get right. Miss any one of them and `Connect` returns `success: true` but no tunnel comes up.

---

## 2. Five wiring points

| # | Where | What you put there |
|---|-------|--------------------|
| 1 | `composer.json` (app) | `"projectmata/mobile-openvpn": "^1.0"` |
| 2 | `app/Providers/NativeServiceProvider.php` | Register `MobileOpenVpnServiceProvider::class` |
| 3 | `nativephp/android/openvpn/` | Clone of `schwabe/ics-openvpn` |
| 4 | `nativephp/android/settings.gradle.kts` | `include(":openvpn:main")` |
| 5 | `nativephp/android/app/build.gradle.kts` | `missingDimensionStrategy(...)` + `implementation(project(":openvpn:main"))` |

Plus the runtime bridge file at `nativephp/android/app/src/main/java/com/projectmata/mobileopenvpn/OpenVpnPlugin.kt` and its registry entries in `PluginBridgeFunctionRegistration.kt`.

---

## 3. Step-by-step install

### Step 1 — Add the Composer package
```bash
composer require projectmata/mobile-openvpn:^1.0
```

Laravel auto-discovery picks up `MobileOpenVpnServiceProvider` from the package's `composer.json` `extra.laravel.providers` block. No manual provider registration is needed in `config/app.php`.

### Step 2 — Register inside your NativePHP service provider
Open `app/Providers/NativeServiceProvider.php` (this is NativePHP's plugin manifest in your app, not Laravel's `app.php`):

```php
public function provides(): array
{
    return [
        // … existing plugins …
        \Projectmata\MobileOpenVpn\MobileOpenVpnServiceProvider::class,
    ];
}
```

Without this, NativePHP's bundler won't ship the bridge metadata.

### Step 3 — Pull ics-openvpn into the Android project
```bash
cd nativephp/android
git clone --depth 1 https://github.com/schwabe/ics-openvpn openvpn
```

This produces `nativephp/android/openvpn/` containing the full ics-openvpn Gradle subproject. The folder must contain `openvpn/main/` (its core runtime module) and `openvpn/build.gradle.kts`.

> Don't run `gradlew` inside the `openvpn` folder — it's there to be **consumed** as a Gradle subproject, not built standalone.

### Step 4 — Wire it into Gradle settings
Edit `nativephp/android/settings.gradle.kts`. You should already have something like this — verify it matches:

```kotlin
rootProject.name = "AndroidPHP"
include(":app")

// ics-openvpn lives in nativephp/android/openvpn/ as a git clone.
val openvpnDir = file("openvpn")
if (openvpnDir.exists()) {
    include(":openvpn:main")
    project(":openvpn:main").projectDir = file("openvpn/main")
}
```

The `if (openvpnDir.exists())` guard means CI environments without the clone won't fail to configure — `Connect` will just return an error at runtime instead.

Also confirm `dependencyResolutionManagement` lists JitPack — ics-openvpn pulls a few transitive deps from there:

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### Step 5 — Wire it into the app module
Edit `nativephp/android/app/build.gradle.kts`. Two things must be present.

**Inside `defaultConfig`** — pick which ics-openvpn build flavors you want. Without these, Gradle errors out with "missing dimension strategy":

```kotlin
defaultConfig {
    // …
    missingDimensionStrategy("implementation", "skeleton")  // headless lib (no UI)
    missingDimensionStrategy("ovpnimpl", "ovpn2")           // ovpn2 protocol stack
    // …
}
```

| Dimension | Options | What to pick |
|-----------|---------|--------------|
| `implementation` | `ui`, `skeleton` | `skeleton` — you don't want ics-openvpn's own UI; you want just the runtime. |
| `ovpnimpl` | `ovpn2`, `ovpn3` | `ovpn2` — mature C OpenVPN 2.x stack. `ovpn3` uses OpenVPN 3 C++ core. Stick with `ovpn2` unless you have a reason. |

**Inside `dependencies`**:

```kotlin
dependencies {
    // …
    implementation(project(":openvpn:main"))
}
```

### Step 6 — Copy the Kotlin bridge into the app module
The Composer package ships the canonical Kotlin source at:

```
packages/projectmata/mobile-openvpn/resources/android/src/main/java/com/projectmata/mobileopenvpn/OpenVpnPlugin.kt
```

NativePHP's installer copies it into the app at:

```
nativephp/android/app/src/main/java/com/projectmata/mobileopenvpn/OpenVpnPlugin.kt
```

If the copy didn't happen (older NativePHP versions, custom build), copy it manually. Both files should be byte-identical.

### Step 7 — Register every bridge method
NativePHP regenerates `nativephp/android/app/src/main/java/com/nativephp/mobile/bridge/plugins/PluginBridgeFunctionRegistration.kt` on each `php artisan native:run`. Confirm it contains six lines for OpenVpn:

```kotlin
import com.projectmata.mobileopenvpn.OpenVpnPlugin

// Plugin: projectmata/mobile-openvpn
registry.register("OpenVpn.IsSupported",      OpenVpnPlugin.IsSupported(activity))
registry.register("OpenVpn.RequestPermission", OpenVpnPlugin.RequestPermission(activity))
registry.register("OpenVpn.Connect",          OpenVpnPlugin.Connect(activity))
registry.register("OpenVpn.Disconnect",       OpenVpnPlugin.Disconnect(activity))
registry.register("OpenVpn.GetStatus",        OpenVpnPlugin.GetStatus(activity))
registry.register("OpenVpn.GetEvents",        OpenVpnPlugin.GetEvents(activity))
```

These six entries come straight from the package's `nativephp.json`.

### Step 8 — Build the mobile app
```bash
php artisan native:run android
```

First build is slow — ics-openvpn ships its own NDK/CMake that compiles the OpenVPN 2.x C source for `arm64-v8a`. Expect 3-8 minutes the first time.

---

## 4. AndroidManifest entries (auto-merged, mostly)

You **don't** need to add `<service android:name="de.blinkt.openvpn.core.OpenVPNService" …>` to your app's manifest. ics-openvpn's `:openvpn:main` module declares it in its own manifest, and Gradle's manifest merger folds it into the final APK.

What **you** declare in `nativephp/android/app/src/main/AndroidManifest.xml` (the package's `nativephp.json` instructs NativePHP's installer to inject these — verify they're present):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

Min SDK must be **≥ 33** (declared in `nativephp.json`). Lower versions miss `BIND_VPN_SERVICE` foreground-service rules.

---

## 5. Place your `.ovpn` profile

The plugin parses `.ovpn` content from a string — it doesn't read the file itself. The convention used in EliteScanner_mobile:

```
resources/vpn/elite.ovpn
```

Then you load it in PHP:

```php
$ovpn = file_get_contents(resource_path('vpn/elite.ovpn'));
```

Or expose it through a route the JS layer calls (`/api/vpn/config/elite.ovpn`) so the frontend can pull it on demand. EliteScanner uses the route approach — see `useVpn.js` lines 35–60.

Things ics-openvpn's `ConfigParser` accepts:
- Inline `<ca>…</ca>`, `<cert>…</cert>`, `<key>…</key>`, `<tls-auth>…</tls-auth>` blocks (recommended).
- `auth-user-pass` (without inline credentials — pass them via `username`/`password` to `Connect`).
- Most standard 2.x directives. Avoid newer 2.6+ flags unless you're on `ovpn3`.

---

## 6. End-to-end usage

### PHP (controllers / commands)
```php
use Projectmata\MobileOpenVpn\Facades\OpenVpn;

OpenVpn::requestPermission();   // shows Android VPN consent dialog (first time only)

$ovpn = file_get_contents(resource_path('vpn/elite.ovpn'));

$result = OpenVpn::connect(
    profile:     $ovpn,
    username:    'alice',
    password:    decrypt($encrypted),
    displayName: 'EliteScanner VPN',
);
// $result === ['success' => true, 'status' => 'starting'|'connected', …]

$status = OpenVpn::getStatus();
// ['success' => true, 'status' => 'connected', 'active' => true, 'transportActive' => true, …]

OpenVpn::disconnect();
```

### JavaScript (Vue/composable)
```js
import OpenVpn from '@projectmata/mobile-openvpn';   // or relative path

await OpenVpn.RequestPermission();

await OpenVpn.Connect({
    profile: ovpnText,
    username: 'alice',
    password: secret,
    displayName: 'EliteScanner VPN',
});

const { status, active } = await OpenVpn.GetStatus();

const { events } = await OpenVpn.GetEvents({ sinceTs: 0, limit: 200 });
//  state transitions + log lines + per-byte traffic from ics-openvpn
```

`GetEvents` is your debugging lifeline — every `AUTH_FAILED`, `NETWORK_UNREACHABLE`, `TLS error`, etc. ics-openvpn emits ends up in that ring buffer (200 entries, oldest dropped first).

---

## 7. Permission flow on Android

```
User taps "Connect"
       ↓
JS: await OpenVpn.RequestPermission()
       ↓
Kotlin: VpnService.prepare(activity) returns Intent (= consent needed)
       ↓
activity.startActivityForResult(intent, 7701)   ← system dialog
       ↓
User taps "OK" in the system dialog
       ↓
onActivityResult(7701, RESULT_OK, …)            ← MainActivity must propagate this
       ↓
JS: await OpenVpn.Connect({...})                ← now allowed
```

If `Connect` returns `OPENVPN_PERMISSION_REQUIRED`, the user denied or hasn't been prompted yet — call `RequestPermission` first.

After the first grant, `VpnService.prepare()` returns `null` and `Connect` proceeds without re-prompting (until the user revokes the profile in Android Settings).

---

## 8. State diagram (what `GetStatus` reports)

| Bridge `status` | ics-openvpn `ConnectionStatus` | Meaning |
|-----------------|--------------------------------|---------|
| `disconnected` | `LEVEL_NOTCONNECTED` | Idle / never connected / cleanly torn down |
| `connecting` | `LEVEL_START`, `LEVEL_CONNECTING_NO_SERVER_REPLY_YET`, `LEVEL_CONNECTING_SERVER_REPLIED`, `LEVEL_WAITING_FOR_USER_INPUT` | Handshake / TLS / waiting for token |
| `connected` | `LEVEL_CONNECTED` | Tunnel up, traffic flowing |
| `auth_failed` | `LEVEL_AUTH_FAILED` | Bad username/password/cert |
| `no_network` | `LEVEL_NONETWORK` | No underlying connectivity |

The plugin also cross-checks with `ConnectivityManager.allNetworks` (`isVpnTransportActive`) so it can detect cases where `VpnStatus` lags reality.

---

## 9. Common build failures & fixes

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Configuration failure: project ':openvpn:main' not found` | Forgot Step 4 | Add the `include(":openvpn:main")` block to `settings.gradle.kts`. |
| `Could not determine the dependencies of task ':app:mergeDebugResources'. > Conflicting flavor dimensions` | Forgot Step 5 `missingDimensionStrategy` | Add both `implementation`/`skeleton` and `ovpnimpl`/`ovpn2`. |
| `Unresolved reference: de.blinkt.openvpn` in `OpenVpnPlugin.kt` | `:openvpn:main` not on classpath | Verify `implementation(project(":openvpn:main"))` and that the clone exists. |
| First build hangs at "Compiling C++" for >10 min | Normal | ics-openvpn compiles OpenVPN 2.x C sources. Subsequent builds reuse the cache. |
| Build OK, app installs, `Connect` returns `success: true`, but no tunnel | The ics-openvpn `OpenVPNService` couldn't start (manifest merge failed) | Run `./gradlew :app:processDebugManifest --info` and grep for `de.blinkt.openvpn.core.OpenVPNService`. If absent, manifest merger is suppressed somewhere — check `tools:node="remove"` markers. |
| `OPENVPN_PERMISSION_REQUIRED` on every connect | The system VPN consent dialog was never shown / was denied | Reset via Android Settings → Network → VPN → ⚙ → Forget VPN, then call `RequestPermission` again. |
| 16 KB page-size warning on Pixel 8+ | NDK linker flag missing | Already handled — `-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384` is in `defaultConfig.externalNativeBuild.cmake.arguments`. |

---

## 10. iOS notes (placeholder)

The package's `nativephp.json` declares iOS bridge functions, but the canonical `OpenVpnPlugin.swift` is a stub. Apple does not ship an OpenVPN runtime; you need:

1. A **Network Extension** target in Xcode.
2. [`OpenVPNAdapter`](https://github.com/ss-abramchuk/OpenVPNAdapter) (SPM/CocoaPods) — note: AGPL.
3. **Personal VPN** + **Network Extensions** entitlements on both targets (paid Apple Developer account required).
4. A `NETunnelProviderProtocol` that passes the `.ovpn` bytes via `providerConfiguration`.

Until that's wired, `Connect` on iOS will return `OPENVPN_NOT_IMPLEMENTED`.

---

## 11. Quick-test script

Drop this into `routes/web.php` for a manual smoke test:

```php
use Projectmata\MobileOpenVpn\Facades\OpenVpn;

Route::get('/vpn-test', function () {
    return [
        'supported' => OpenVpn::isSupported(),
        'status'    => OpenVpn::getStatus(),
    ];
});
```

Run on the device's WebView and you should see:

```json
{
  "supported": { "success": true, "supported": true },
  "status":    { "success": true, "status": "disconnected", "active": false, "transportActive": false }
}
```

If `supported.success === false` with a `nativephp_call` error, the bridge isn't reaching the device — re-run `php artisan native:run` and inspect the regenerated `PluginBridgeFunctionRegistration.kt`.

---

## 12. Licensing caveats

- **ics-openvpn** is **GPL-v2**. Bundling it into your APK propagates GPL to your entire app: you must publish full source on request, and you can't combine it with proprietary closed-source modules without violating the license.
- **`projectmata/mobile-openvpn` itself is MIT** (PHP/JS/Kotlin scaffolding only — no GPL code in the package). It only becomes GPL-tainted when you link ics-openvpn alongside it.
- Alternatives if GPL is a dealbreaker:
  - Switch to **WireGuard** (`wireguard-android`, Apache 2.0) — different protocol but cleaner license.
  - Use **OpenVPN 3 Core** directly (Apache 2.0) — significantly more integration work.

Document your choice in your app's `NOTICE` / `LICENSE` file before shipping.

---

## 13. Files of interest in this repo

| File | What it is |
|------|------------|
| `packages/projectmata/mobile-openvpn/composer.json` | Package manifest (Laravel auto-discovery) |
| `packages/projectmata/mobile-openvpn/nativephp.json` | NativePHP bridge function metadata |
| `packages/projectmata/mobile-openvpn/src/MobileOpenVpnServiceProvider.php` | Laravel provider — binds singleton |
| `packages/projectmata/mobile-openvpn/src/OpenVpnManager.php` | PHP API → calls `nativephp_call(...)` |
| `packages/projectmata/mobile-openvpn/src/Facades/OpenVpn.php` | Laravel Facade |
| `packages/projectmata/mobile-openvpn/resources/js/index.js` | Browser bridge — `fetch` to `/_native/api/call` |
| `packages/projectmata/mobile-openvpn/resources/android/.../OpenVpnPlugin.kt` | Canonical Kotlin source |
| `packages/projectmata/mobile-openvpn/resources/ios/.../OpenVpnPlugin.swift` | iOS stub |
| `nativephp/android/openvpn/` | **ics-openvpn clone (GPL)** — git ignored, recreate per machine |
| `nativephp/android/settings.gradle.kts` | `include(":openvpn:main")` |
| `nativephp/android/app/build.gradle.kts` | `missingDimensionStrategy` + `implementation(project(":openvpn:main"))` |
| `nativephp/android/app/src/main/java/com/projectmata/mobileopenvpn/OpenVpnPlugin.kt` | Active copy bridge runtime calls |
| `nativephp/android/app/src/main/java/com/nativephp/mobile/bridge/plugins/PluginBridgeFunctionRegistration.kt` | Auto-generated registry (6 `OpenVpn.*` lines) |
| `app/Providers/NativeServiceProvider.php` | Lists `MobileOpenVpnServiceProvider::class` in `provides()` |
| `resources/vpn/elite.ovpn` | The actual VPN profile |
| `resources/js/composables/useVpn.js` | Vue composable wrapping `OpenVpn.*` |

---

## 14. TL;DR install (cheatsheet)

```bash
# 1. PHP package
composer require projectmata/mobile-openvpn:^1.0

# 2. Add to NativePHP provider list (manual edit)
#    app/Providers/NativeServiceProvider.php → provides() array

# 3. Clone ics-openvpn
cd nativephp/android
git clone --depth 1 https://github.com/schwabe/ics-openvpn openvpn

# 4. Verify settings.gradle.kts has:
#    include(":openvpn:main")
#    project(":openvpn:main").projectDir = file("openvpn/main")

# 5. Verify app/build.gradle.kts has:
#    defaultConfig {
#        missingDimensionStrategy("implementation", "skeleton")
#        missingDimensionStrategy("ovpnimpl", "ovpn2")
#    }
#    dependencies { implementation(project(":openvpn:main")) }

# 6. Drop your profile
mkdir -p resources/vpn
cp /path/to/your.ovpn resources/vpn/elite.ovpn

# 7. Build & run
php artisan native:run android
```

If the smoke test in §11 returns `supported: true` and `status: "disconnected"`, you're done.
