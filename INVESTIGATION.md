# Symfony timeout investigation — 2026-10-08

## Finding

The reported Roadsight failure is NativePHP terminating `composer install --no-interaction` after 300 seconds while preparing its temporary Laravel bundle. Symfony reports the timeout; the deadline is explicitly set by NativePHP. This happens before Android plugin compilation and before this package's Android preparation hook executes.

The investigation found a substantial delay during initial optimized autoloader generation across the application's dependency tree. An isolated installation containing OpenVPN completed in 214.17 seconds with scripts/plugins disabled, then completed in 15.29 seconds on a repeated run with scripts/plugins enabled. OpenVPN package discovery succeeded. This supports filesystem/cache-sensitive Composer work as the primary bottleneck, rather than a deterministic OpenVPN provider or hook hang.

The original 300-second failure was not reproduced in the isolated fixture. Its exact last internal Composer operation is unavailable because NativePHP only writes subprocess output to its build log after `run()` returns without throwing. Antivirus, disk contention, cache state and the original machine workload are possible contributors; none has been individually established as the cause.

## Relevant application and package versions

- Failing application supplied by the user: `C:/laragon/www/Roadsight_mobile`.
- NativePHP Mobile: 4.6.0.
- PHP CLI: 8.4.12; Composer: 2.9.7; Windows.
- Retained bundle: `C:/temp/1791441033`, containing OpenVPN v1.0.1, reference `952d5d8e86e9073b10276daafdce00c037c821c7`.
- That bundle's provider, command, installer, AAR and engine source archive match the investigated local package by SHA-256.
- Roadsight's current `composer.json`, lock file and vendor directory no longer contain OpenVPN. Its current successful build log therefore supplies comparison evidence only.
- EliteScanner's installed OpenVPN v1.0.0 is a separate older distribution. Its historical 60-second autoloader/ADB errors are separate from the reported Roadsight error.

## Execution path and timeout ownership

1. `vendor/nativephp/mobile/src/Concerns/RunsAndroid.php:120` includes development dependencies for debug builds.
2. `RunsAndroid.php:122` calls Android build preparation, which prepares the Laravel bundle.
3. `Concerns/PreparesBuild.php:254` copies the app into a new temporary directory using `BundleFileManager`.
4. `PreparesBuild.php:260-262` starts Composer in that directory with `Process::path($tempDir)->timeout(300)`.
5. Root Composer configuration enables optimized autoload generation. The retained bundle has 168 installed packages, including 54 development packages, and approximately 11,589 PHP files under vendor.
6. Composer then executes Laravel's `post-autoload-dump` scripts, including `artisan package:discover`.
7. NativePHP later performs another authoritative autoloader dump with a separate 60-second deadline at `PreparesBuild.php:279-282`.
8. Only after bundle preparation does `RunsAndroid.php:124` compile Android plugins. `Plugins/Compilers/AndroidPluginCompiler.php:288` invokes `post_compile`, which runs OpenVPN's Android installer.

Changing `COMPOSER_PROCESS_TIMEOUT` or PHP's `max_execution_time` does not override the outer NativePHP deadline. Composer's own subprocess timeout is a separate setting.

## Package audit

- `composer.json` autoloads only `src/`; it has no Composer scripts, Composer-plugin implementation, network download, or installation subprocess.
- `MobileOpenVpnServiceProvider::register()` registers a lazy singleton. It does not connect a VPN.
- `boot()` registers the preparation command in console contexts. Registering the command does not run its `handle()` method.
- `PrepareAndroidCommand` runs only when invoked. The hook is declared as `post_compile` in `nativephp.json`.
- `AndroidEngineInstaller` reads Gradle/manifest inputs, checks hashes, copies the bundled AAR if necessary, and writes Gradle wiring. It starts no subprocesses and makes no network requests.
- PHP bridge calls and JavaScript connection polling are explicit runtime API operations. Neither runs during `composer install`; their VPN timeouts are separate from Symfony's process timeout.
- The package contains a 17.11 MiB AAR and a 25.52 MiB engine source archive. These increase copying and bundle size, but are outside the package's Composer autoload directory. The archive contents are not PHP classmap inputs.
- `GUIDE.md` preserves the older manual engine-checkout workflow, with an explicit notice to use the current README and avoid combining that workflow with the bundled AAR. Neither workflow runs during Composer package discovery.

## Measurements and verification

All mutation for diagnostic execution was confined to isolated temporary fixtures. No application dependency, source, native project, or timeout setting was changed, and no APK was installed or VPN connected.

| Check | Result |
| --- | --- |
| Existing `php mobile-openvpn/tests/installer.php` | All 8 checks passed, including hash validation, idempotence and controlled rejection paths |
| Existing `node --test --test-isolation=none mobile-openvpn/tests/connect.test.mjs` | 10/10 passed with a mocked bridge |
| Isolated Laravel hook using NativePHP 3.2.6 | Exit 0; AAR installed; approximately 3.50 seconds |
| Isolated Laravel hook using Roadsight's NativePHP 4.6.0 | Exit 0; AAR installed; approximately 3.93 seconds |
| Composer class scan of local OpenVPN `src/` | 5 classes; approximately 0.009 seconds |
| Initial read-only scan of EliteScanner's full PSR-4 tree | Approximately 127.54 seconds; OpenVPN contributed approximately 0.004 seconds |
| Repeated read-only scan of that PSR-4 tree | Approximately 4 seconds overall; this is not a full Composer install benchmark |
| Roadsight bundle copy, OpenVPN included: `composer install --no-interaction --no-scripts --no-plugins --profile -vvv` | Exit 0; 214.17 seconds; reached optimized autoloader generation at 2.32 seconds; nothing to install/download |
| Same isolated fixture, repeated `composer install --no-interaction --profile -vvv` | Exit 0; 15.29 seconds; autoload completed around 11.47 seconds; Laravel discovery completed around 15.28 seconds; OpenVPN discovered successfully |
| Roadsight latest existing build log, OpenVPN absent | Composer installation took approximately 213 seconds; authoritative dump approximately 13 seconds; native build/install later succeeded |

The first/repeated Composer runs differ in cache state and script/plugin flags; they are not an A/B experiment proving a particular Windows service is responsible. Nevertheless, the slow run had scripts disabled and required no dependency downloads, locating its measured delay in autoloader generation rather than Laravel discovery or engine installation.

The first Node test invocation was blocked by sandbox child-process restrictions (`spawn EPERM`). Running the same suite without test-process isolation succeeded. An initial synthetic Laravel fixture lacked the console-kernel binding; rebuilding that fixture with Laravel's application builder resolved the harness error. Neither was an application/package failure.

## Recommended corrective work

1. Make NativePHP's outer Composer install deadline configurable and bounded; 900 seconds is a reasonable initial build allowance on this machine. Also address its independent 60-second authoritative autoloader deadline, since another initial filesystem scan can exceed it. Keep failure handling and log the subprocess duration.
2. Stream Composer output into the build log while it runs and preserve partial output on timeout. Add `--profile` when diagnosing. That establishes the exact stalled operation in a future failing build instead of losing it when the deadline is reached.
3. Investigate filesystem performance on freshly copied bundle trees, comparing first/repeated runs under comparable workloads. Check disk activity and security-software scanning before attributing the slowdown to either; disabling protection was not tested or recommended as a blanket remedy.
4. Reduce shipped native build inputs in the PHP bundle. App-level `nativephp.cleanup_exclude_files` can target `vendor/projectmata/mobile-openvpn/resources/android`, since the native compiler reads the original installed package after bundle preparation. Confirm that against the consuming app before applying it. Retain the AAR and matching engine source in the Composer distribution; excluding them from that distribution would break installation or source availability. This can reduce copy/archive/APK size, but does not remove the measured PHP classmap bottleneck.
5. Review debug dependency overhead. NativePHP intentionally includes development dependencies for debug builds; other supported build types exclude them. Any change must preserve the app's development workflow and avoid silently changing the build type.

No package runtime patch is justified by the observed timeout. The demonstrated package paths complete successfully; the needed timeout and diagnostic changes belong to the NativePHP build runner or its maintained application integration.

## Documentation references

- [Symfony Process timeout](https://symfony.com/doc/current/components/process.html#process-timeout)
- [Composer process-timeout configuration](https://getcomposer.org/doc/06-config.md#process-timeout)
- [Composer CLI options and autoload generation](https://getcomposer.org/doc/03-cli.md#dump-autoload-dumpautoload)

This investigation does not certify an Android compile, device tunnel, server reachability, or production deployment.
