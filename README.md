# Projectmata Mobile OpenVPN

[![Latest Version](https://img.shields.io/packagist/v/projectmata/mobile-openvpn.svg)](https://packagist.org/packages/projectmata/mobile-openvpn)
[![Total Downloads](https://img.shields.io/packagist/dt/projectmata/mobile-openvpn.svg)](https://packagist.org/packages/projectmata/mobile-openvpn)
[![License](https://img.shields.io/packagist/l/projectmata/mobile-openvpn.svg)](https://packagist.org/packages/projectmata/mobile-openvpn)

OpenVPN client plugin for [NativePHP Mobile](https://nativephp.com). Lets a Laravel + NativePHP mobile app bring up, tear down, and inspect an OpenVPN tunnel on Android and iOS.

> **Heads-up — native runtime required.**
> Unlike biometrics or geolocation, neither Android nor iOS ship an OpenVPN
> implementation. This package scaffolds the bridge, PHP facade, and JS API
> but you must integrate an OpenVPN runtime (see [Native integration](#native-integration)) before `Connect` actually opens a tunnel.

## Requirements

- PHP `^8.1`
- Laravel `^11.0` or `^12.0` / `^13.0`
- `nativephp/mobile`
- Android: `min_version 33`
- iOS: `min_version 18.2` + a Network Extension target in your app

## Installation

```bash
composer require projectmata/mobile-openvpn
```

Laravel auto-discovery registers the service provider and facade. Rebuild the mobile app so NativePHP bundles the native plugin:

```bash
php artisan native:run android
# or
php artisan native:run ios
```

## Usage

### PHP (Laravel)

```php
use Projectmata\MobileOpenVpn\Facades\OpenVpn;

// 1. Prompt the user for VPN permission (Android only — iOS no-ops)
OpenVpn::requestPermission();

// 2. Start a tunnel from an .ovpn profile string
$ovpn = file_get_contents(storage_path('vpn/client.ovpn'));

$result = OpenVpn::connect(
    profile: $ovpn,
    username: 'alice',
    password: $credential,
    displayName: 'Corporate VPN',
);

// 3. Check status
$status = OpenVpn::getStatus();
// ['success' => true, 'status' => 'connected' | 'connecting' | 'disconnected']

// 4. Tear down
OpenVpn::disconnect();
```

### JavaScript (in-app)

The plugin registers itself on `window.NativePHP.OpenVpn`:

```js
await window.NativePHP.OpenVpn.RequestPermission();

const result = await window.NativePHP.OpenVpn.Connect({
    profile: ovpnString,
    username: 'alice',
    password: credential,
    displayName: 'Corporate VPN',
});

const { status } = await window.NativePHP.OpenVpn.GetStatus();

await window.NativePHP.OpenVpn.Disconnect();
```

## Bridge methods

| Method                          | Params                                              | Returns                                      |
| ------------------------------- | --------------------------------------------------- | -------------------------------------------- |
| `OpenVpn.IsSupported`           | —                                                   | `{ success, supported }`                     |
| `OpenVpn.RequestPermission`     | —                                                   | `{ success, granted, message? }`             |
| `OpenVpn.Connect`               | `{ profile, username?, password?, displayName? }`   | `{ success, status, displayName }`           |
| `OpenVpn.Disconnect`            | —                                                   | `{ success, status }`                        |
| `OpenVpn.GetStatus`             | —                                                   | `{ success, status }`                        |

`status` is one of `disconnected`, `connecting`, or `connected`.

## Native integration

### Android

This package does not declare a default OpenVPN runtime dependency because popular Android OpenVPN implementations are not published as ordinary Maven Central artifacts. Vendor [ics-openvpn](https://github.com/schwabe/ics-openvpn) or your chosen runtime into your app, then update `resources/android/src/main/java/com/projectmata/mobileopenvpn/OpenVpnPlugin.kt` `Connect.execute()` to:

1. Parse the `.ovpn` profile via `ConfigParser`.
2. Build a `VpnProfile`.
3. Start `OpenVPNService` with `VPNLaunchHelper.startOpenVpn(profile, context)`.

Also wire an Activity Result callback if your runtime needs to report back once the user grants consent.

### iOS

Apple does not provide OpenVPN. Add a **Network Extension** target to your Xcode project backed by [`OpenVPNAdapter`](https://github.com/ss-abramchuk/OpenVPNAdapter) (SPM or CocoaPods). In `Connect.execute()`:

1. Create a `NETunnelProviderProtocol` whose `providerBundleIdentifier` matches your NE target.
2. Pass the `.ovpn` bytes, username, and password via `providerConfiguration`.
3. `saveToPreferences` → `connection.startVPNTunnel()`.

You'll also need the **Personal VPN** and **Network Extensions** entitlements on both the host app and the NE target, plus a matching provisioning profile (Apple does not grant these to free accounts).

## Permissions

- **Android**: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` are declared by this package. The system additionally shows a VPN consent dialog the first time you call `RequestPermission()`.
- **iOS**: No runtime permission prompt; the user sees a **"Allow [App] to Add VPN Configurations?"** system dialog the first time the profile is saved.

## Licensing caveats

Most Android OpenVPN stacks (ics-openvpn, openvpn-for-android) are **GPL-v2**. Linking them into your app may propagate GPL obligations to your app binary. If that is a concern, consider:

- A permissively-licensed alternative (OpenVPN3 core, Apache 2.0 — more work to integrate).
- Switching to WireGuard, which has a permissive implementation on both platforms.

`OpenVPNAdapter` on iOS wraps openvpn3 and is typically AGPL — review before shipping commercially.

## License

MIT (scaffold code). The OpenVPN runtime you link against carries its own license — see above.
