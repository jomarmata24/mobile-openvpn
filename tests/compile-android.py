"""Compile the real Kotlin plugin + bundled AAR against NativePHP's bridge.

Creates an isolated test app; does not install it or connect any VPN.
Usage: python tests/compile-android.py PATH_TO_NATIVEPHP_MOBILE
Requires the host Android/JDK/Gradle toolchain and cached Maven dependencies.
"""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

package = Path(__file__).resolve().parents[1]
host = Path(sys.argv[1]).resolve()
root = Path(tempfile.mkdtemp(prefix='projectmata-openvpn-compile-')).resolve()
app = root / 'app'
sources = app / 'src/main/java'
(sources / 'com/projectmata/mobileopenvpn').mkdir(parents=True)
(sources / 'com/nativephp/mobile/bridge').mkdir(parents=True)
shutil.copyfile(package / 'resources/android/src/main/java/com/projectmata/mobileopenvpn/OpenVpnPlugin.kt',
                sources / 'com/projectmata/mobileopenvpn/OpenVpnPlugin.kt')
shutil.copyfile(host / 'resources/androidstudio/app/src/main/java/com/nativephp/mobile/bridge/BridgeRouter.kt',
                sources / 'com/nativephp/mobile/bridge/BridgeRouter.kt')
(root / 'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "OpenVpnPackageCompileTest"
include(":app")
''')
(root / 'build.gradle.kts').write_text('''plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
}
''')
(root / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2048m\nkotlin.compiler.execution.strategy=in-process\n')
(app / 'build.gradle.kts').write_text('''plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.projectmata.openvpn.test"
    compileSdk = 36
    defaultConfig { applicationId = "com.projectmata.openvpn.test"; minSdk = 33; targetSdk = 36 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.fragment:fragment:1.5.4") }
''')
(app / 'src/main/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="OpenVPN package compile test" /></manifest>')
sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
if not sdk:
    raise SystemExit('ANDROID_SDK_ROOT or ANDROID_HOME is required.')
(root / 'local.properties').write_text('sdk.dir=' + Path(sdk).as_posix() + '\n')
installer = root / 'install.php'
installer.write_text('''<?php
require $argv[1].'/src/Build/AndroidEngineInstaller.php';
(new Projectmata\\MobileOpenVpn\\Build\\AndroidEngineInstaller())->install($argv[2], $argv[1]);
''')
subprocess.run(['php', str(installer), str(package), str(root)], check=True)
gradles = list((Path.home() / '.gradle/wrapper/dists/gradle-8.13-bin').glob('*/gradle-8.13/bin/gradle.bat'))
if not gradles:
    raise SystemExit('Gradle 8.13 is not cached; use a provisioned Gradle 8.13 toolchain.')
print('Compile fixture:', root, flush=True)
flags = [] if '--online' in sys.argv[2:] else ['--offline']
environment = os.environ.copy()
environment['DEBUG'] = ''
result = subprocess.run([str(gradles[0]), ':app:assembleDebug', *flags, '--no-daemon', '--console=plain'], cwd=root, env=environment)
if result.returncode == 0:
    print('APK:', app / 'build/outputs/apk/debug/app-debug.apk', flush=True)
raise SystemExit(result.returncode)
