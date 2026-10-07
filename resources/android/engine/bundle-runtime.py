"""Bundle the locally patched engine AAR and its source; never downloads anything.

Usage: python bundle-runtime.py PATH_TO_ICS_OPENVPN_CHECKOUT
Run this only when maintaining/releasing the plugin, not in a consuming app.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import zipfile

engine = Path(sys.argv[1]).resolve()
package = Path(__file__).resolve().parents[3]
aar = engine / 'main/build/outputs/aar/main-skeleton-ovpn2-release.aar'
output = package / 'resources/android/libs/projectmata-openvpn.aar'
sources = package / 'resources/android/engine/ics-openvpn-sources.zip'
manifest = '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="de.blinkt.openvpn">
  <uses-sdk android:minSdkVersion="21" />
  <uses-permission android:name="android.permission.INTERNET" />
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
  <application android:name="de.blinkt.openvpn.core.ICSOpenVPNApplication">
    <service android:name="de.blinkt.openvpn.core.OpenVPNService" android:exported="true"
      android:permission="android.permission.BIND_VPN_SERVICE" android:foregroundServiceType="specialUse" android:process=":openvpn">
      <intent-filter><action android:name="android.net.VpnService" /></intent-filter>
      <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="vpn" />
    </service>
    <service android:name="de.blinkt.openvpn.core.OpenVPNStatusService" android:exported="false" android:process=":openvpn" />
  </application>
</manifest>
'''

def write_entry(archive, name, data):
    entry = zipfile.ZipInfo(name, (2026, 10, 8, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    archive.writestr(entry, data)

with zipfile.ZipFile(aar) as original, zipfile.ZipFile(output, 'w') as result:
    for name in sorted(original.namelist()):
        if name.endswith('/'):
            continue
        write_entry(result, name, manifest.encode() if name == 'AndroidManifest.xml' else original.read(name))

# Include upstream source, all native submodule sources, local patches and notices.
# Exclude git metadata, IDE files and generated build outputs; never include app profiles.
excluded = {'.git', '.gradle', '.idea', '.github', 'build', '.cxx', '__pycache__'}
with zipfile.ZipFile(sources, 'w') as archive:
    for directory, folders, names in os.walk(engine):
        folders[:] = sorted(folder for folder in folders if folder not in excluded)
        relative_dir = Path(directory).relative_to(engine)
        if not relative_dir.parts:
            folders[:] = [folder for folder in folders if folder in {'main', 'doc', 'gradle', 'remoteExample', 'tlsexternalcertprovider'}]
        for name in sorted(names):
            path = Path(directory) / name
            relative = path.relative_to(engine)
            if name in excluded or path.suffix.lower() in {'.ovpn', '.p12', '.jks', '.keystore'}:
                continue
            data = path.read_bytes()
            # Match the distributed AAR's embedded-only manifest on rebuild.
            if relative.as_posix() == 'main/src/main/AndroidManifest.xml':
                data = manifest.encode()
            if relative.as_posix() == 'main/src/skeleton/AndroidManifest.xml':
                data = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application /></manifest>\n'
            write_entry(archive, relative.as_posix(), data)

(package / 'resources/android/engine/LICENSE.txt').write_bytes((engine / 'doc/LICENSE.txt').read_bytes())

def git(*args):
    return subprocess.check_output(['git', '-C', str(engine), *args], text=True).strip()

metadata = {
    'upstream': 'https://github.com/schwabe/ics-openvpn',
    'commit': git('rev-parse', 'HEAD'),
    'submodules': git('submodule', 'status'),
    'local_patch_files': git('diff', '--name-only').splitlines(),
    'manifest': 'Embedded services only; standalone UI/exported API removed.',
    'aar_sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
    'sources_sha256': hashlib.sha256(sources.read_bytes()).hexdigest(),
    'abis': ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'],
}
(package / 'resources/android/engine/runtime.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(json.dumps({'aar_bytes': output.stat().st_size, 'source_bytes': sources.stat().st_size, 'commit': metadata['commit']}))
