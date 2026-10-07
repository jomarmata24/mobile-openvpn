# Projectmata Mobile OpenVPN

Android OpenVPN client for NativePHP Mobile 3.2+ and 4.x. The package includes the patched **ics-openvpn runtime**, its native executable/libraries and corresponding source. NativePHP registration and a native rebuild remain part of normal plugin installation; consuming apps do not need to clone an engine, edit Gradle, add a VPN service, or install engine NDK dependencies.

**Platform support:** Android 13+ with compile SDK 36+. The bundled runtime includes arm64-v8a, armeabi-v7a, x86 and x86_64; your host app's ABI filters still apply. iOS explicitly reports unsupported until a real Packet Tunnel Provider is included.

## Installation

Configure the source for this modified package, then install/register it as usual:

```powershell
composer require projectmata/mobile-openvpn
php artisan vendor:publish --tag=nativephp-plugins-provider --no-interaction
php artisan native:plugin:register projectmata/mobile-openvpn --no-interaction
php artisan native:plugin:list
```

Build the native app manually with `php artisan native:run android`. Updating PHP/JS or opening the application in a browser cannot add native functionality to an existing APK. These local changes have not been published to Packagist: `composer require` must resolve this updated package, not an older published version.

If the app already has an older copied installation of this local package, refresh it with `composer reinstall projectmata/mobile-openvpn --no-interaction` after confirming its Composer path repository points to this updated source. EliteScanner currently has such an older `vendor` copy; editing the source package does not automatically replace it. Register the plugin if not already registered, then rebuild.

## Supply profile text and connect

Import the wrapper from your Composer installation. This path is for a module directly under `resources/js`; adjust it for deeper modules or use an application alias.

```js
import OpenVpn from '../../vendor/projectmata/mobile-openvpn/resources/js/index.js';

// ovpnText is the administrator-issued .ovpn CONTENT, not a path or URL.
const state = await OpenVpn.ConnectProfile(ovpnText);
console.log(state.status); // connected, after the native engine reports it

await OpenVpn.Disconnect();
```

If the profile/server uses username/password authentication:

```js
await OpenVpn.ConnectProfile(ovpnText, {
    username: vpnUsername,
    password: vpnPassword,
    displayName: 'Roadsight VPN',
});
```

A valid profile must include the required trust material and client identity, or the app must supply the required credentials. A profile referring to files on the administrator's computer is not self-contained: supply inline certificate/key material where the profile format permits. Profiles requiring username/password without effective supplied or embedded credentials fail early with `OPENVPN_CREDENTIALS_REQUIRED`. PKCS12 profiles need a separate Android keystore import and are explicitly rejected with `OPENVPN_UNSUPPORTED_PROFILE`; ask the administrator for inline PEM certificate/key material for the profile-only flow. Server-required identity cannot be generated or bypassed by the plugin. Do not place credentials in `VITE_*` variables; use secure provisioning/storage.

`ConnectProfile` checks native support, opens the Android system VPN-consent dialog when needed, waits for consent, launches the engine once, then polls native engine status. It rejects authentication/network errors, stops a launched engine on timeout/cancellation, and serializes connection/disconnection work. The OS consent dialog cannot be bypassed.

Optional controls:

```js
const abort = new AbortController();
await OpenVpn.ConnectProfile(ovpnText, {
    permissionTimeoutMs: 60000,
    connectionTimeoutMs: 60000,
    pollIntervalMs: 750,
    signal: abort.signal,
});
// abort.abort() cancels an in-progress orchestration request.
```

A connected tunnel does not prove your private API/scanner is reachable. Verify that service separately. The package establishes the connection on your explicit call; it does not invent app login/background/reconnect policies.

## Automatic native integration

NativePHP invokes the package's `post_compile` hook on every Android plugin build. The hook:

1. Validates the generated Android project and supported SDK.
2. Verifies the bundled AAR and source archive against `resources/android/engine/runtime.json` SHA-256 hashes.
3. Copies the AAR into `app/libs/projectmata-openvpn.aar`.
4. Adds/replaces a managed Gradle block and dependency script.
5. Configures native executable extraction and shared C++ library conflict handling.
6. Replaces EliteScanner's exact old `implementation(project(":openvpn:main"))` dependency to avoid duplicate engine classes. It retains the old source directory.

The AAR supplies the actual engine and VPN/status services. Its embedded-only manifest removes the standalone app UI/exported external API and unnecessary storage/package-query permissions. It preserves the engine Application initialization, protected VPN service, status process and foreground-service permissions.

No runtime download is performed during an app build. The wrapper requires the engine Application initialization; apps with an existing custom Android Application class receive an explicit conflict error instead of having their initialization overwritten. Such hosts need a deliberate integration. Default inspected EliteScanner/Roadsight native hosts have no custom Application class.

Hook mechanism: [NativePHP lifecycle hooks](https://nativephp.com/docs/mobile/4/plugins/lifecycle-hooks).

## Existing low-level API remains available

```js
await OpenVpn.RequestPermission();
const launched = await OpenVpn.Connect({ profile: ovpnText, username, password });
const status = await OpenVpn.GetStatus();
const events = await OpenVpn.GetEvents({ sinceTs: 0, limit: 100 });
```

`Connect` is the original launch-only API. Prefer `ConnectProfile` for the automatic permission/wait flow. The JS `Disconnect` cancels pending high-level connection work, issues the native stop request, and waits up to ten seconds for the binder stop callback and stopped engine status. It blocks a new high-level connection until that stop completes. The PHP/native low-level stop response remains asynchronous.

The PHP facade remains available:

```php
use Projectmata\MobileOpenVpn\Facades\OpenVpn;

OpenVpn::requestPermission();
$result = OpenVpn::connect($profileText, $username, $password, 'Roadsight VPN');
$status = OpenVpn::getStatus();
$events = OpenVpn::getEvents(sinceTs: 0, limit: 100);
OpenVpn::disconnect();
```

PHP calls return the bridge's native result and do not perform the JS consent/connection polling. Use the JS helper for interactive one-call connections.

## Errors and status

Common error codes: `OPENVPN_BAD_PROFILE`, `OPENVPN_UNSUPPORTED`, `OPENVPN_UNSUPPORTED_PROFILE`, `OPENVPN_CREDENTIALS_REQUIRED`, `OPENVPN_BUSY`, `OPENVPN_PERMISSION_TIMEOUT`, `OPENVPN_AUTH_FAILED`, `OPENVPN_NO_NETWORK`, `OPENVPN_CONNECTION_TIMEOUT`, `OPENVPN_DISCONNECT_TIMEOUT`, `OPENVPN_CANCELLED`. Bridge/native errors preserve their codes when supplied.

Engine status is based on this engine's callbacks, rather than declaring success because some other VPN transport is active. Optional credentials use string type checks so NativePHP's JSON-null placeholder cannot become a username/password containing the literal text `null`. Parsed PEM certificate identity is preserved; unsupported PKCS12 inputs are rejected explicitly. Reconnecting with the same display name reuses the saved profile identity.

Native events are an in-memory ring buffer of up to 200 entries. Avoid sharing unredacted diagnostics. Automatic app-level reconnect, always-on policy, a private-service health check, profile provisioning and credentials remain application/server responsibilities.

## Tests and verification

```powershell
node --test --test-isolation=none tests/connect.test.mjs
php tests/installer.php
php tests/hook.php PATH_TO_LARAVEL_HOST
```

The JS tests cover orchestration failures, startup cancellation and deferred disconnection. Installer tests operate on temporary Android projects and do not connect a VPN. The hook test exercises real Laravel command registration using a host's NativePHP installation without reinstalling the package into that host.

An optional isolated Android compilation test uses the installed NativePHP bridge, real Kotlin plugin and bundled AAR:

```powershell
python tests/compile-android.py PATH_TO_VENDOR_NATIVEPHP_MOBILE
```

It requires Android SDK 36, JDK 17, cached Gradle 8.13 and Maven dependencies. It compiles a disposable test APK without installing it. A successful compile is not a physical-device connection test. Verify consent, native connected state, private endpoint access, background behavior and disconnect using an administrator-issued test profile on a device before shipping.

## Engine maintenance and licensing

The PHP/JS/Kotlin wrapper is MIT. **The bundled ics-openvpn engine has GPL v2 terms with additional terms and an OpenSSL linking exception; its third-party components carry their own licenses.** Composer lists both MIT and GPL-2.0-only to avoid presenting this combined distribution as wholly MIT. Preserve the notices and corresponding source requirements when distributing derivatives.

- Engine notice: `resources/android/engine/LICENSE.txt`
- Complete source snapshot: `resources/android/engine/ics-openvpn-sources.zip`
- Source revision/submodules/local modifications and SHA-256 hashes: `resources/android/engine/runtime.json`
- Upstream: [schwabe/ics-openvpn](https://github.com/schwabe/ics-openvpn)

The runtime archive is taken from EliteScanner's existing locally patched release AAR, with its manifest narrowed for embedding. The source snapshot contains engine/native dependencies and its standalone Gradle build inputs. To maintain the runtime, extract/rebuild the source with its specified toolchain, inspect the resulting AAR, then run:

```powershell
python resources/android/engine/bundle-runtime.py PATH_TO_PATCHED_ICS_OPENVPN_CHECKOUT
```

This maintainer-only command refreshes the AAR, source distribution, notices and hashes. Do not publish a source-only Composer archive that omits the `.aar` or source ZIP. No publication or physical-device VPN connection is performed by this package update.
