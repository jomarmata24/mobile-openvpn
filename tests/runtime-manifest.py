"""Check that the distributed engine can register its persisted keep-alive job."""
from pathlib import Path
from xml.etree import ElementTree as ET
import io
import json
import hashlib
import zipfile
import struct

def runtime_paths(binary):
    """Read ELF dynamic string tags without depending on an installed NDK."""
    bits = binary[4]
    endian = '<' if binary[5] == 1 else '>'
    if bits == 2:
        offset = struct.unpack_from(endian + 'Q', binary, 40)[0]
        stride, count = struct.unpack_from(endian + 'HH', binary, 58)
        section_format, dynamic_format = 'IIQQQQIIQQ', 'QQ'
    else:
        offset = struct.unpack_from(endian + 'I', binary, 32)[0]
        stride, count = struct.unpack_from(endian + 'HH', binary, 46)
        section_format, dynamic_format = 'IIIIIIIIII', 'II'
    sections = [struct.unpack_from(endian + section_format, binary, offset + i * stride) for i in range(count)]
    for section in sections:
        if section[1] != 6:
            continue
        strings = sections[section[6]]
        for position in range(section[4], section[4] + section[5], struct.calcsize(dynamic_format)):
            tag, value = struct.unpack_from(endian + dynamic_format, binary, position)
            if tag in (15, 29):
                start = strings[4] + value
                yield binary[start:binary.index(b'\0', start)].decode()

package = Path(__file__).resolve().parents[1]
android = '{http://schemas.android.com/apk/res/android}'
with zipfile.ZipFile(package / 'resources/android/libs/projectmata-openvpn.aar') as archive:
    manifest = ET.fromstring(archive.read('AndroidManifest.xml'))
    services = {service.get(android + 'name'): service for service in manifest.findall('application/service')}
    job = services.get('de.blinkt.openvpn.core.keepVPNAlive')
    assert job is not None, 'OpenVPN startup schedules keepVPNAlive, but its JobService is missing'
    assert job.get(android + 'permission') == 'android.permission.BIND_JOB_SERVICE'
    assert job.get(android + 'exported') == 'true'
    assert job.get(android + 'process') == ':openvpn'
    permissions = {entry.get(android + 'name') for entry in manifest.findall('uses-permission')}
    assert 'android.permission.RECEIVE_BOOT_COMPLETED' in permissions, 'Persisted jobs require boot permission'
    with zipfile.ZipFile(io.BytesIO(archive.read('classes.jar'))) as classes:
        assert 'de/blinkt/openvpn/core/keepVPNAlive.class' in classes.namelist()
    for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
        assert any('$ORIGIN' in path.split(':') for path in runtime_paths(archive.read(f'jni/{abi}/libovpnexec.so'))), \
            f'{abi}: executable must find its sibling libopenvpn.so without LD_LIBRARY_PATH'
with zipfile.ZipFile(package / 'resources/android/engine/ics-openvpn-sources.zip') as sources:
    source_manifest = ET.fromstring(sources.read('main/src/main/AndroidManifest.xml'))
    assert any(service.get(android + 'name') == 'de.blinkt.openvpn.core.keepVPNAlive'
               for service in source_manifest.findall('application/service'))
metadata = json.loads((package / 'resources/android/engine/runtime.json').read_text())
for relative, key in [('resources/android/libs/projectmata-openvpn.aar', 'aar_sha256'),
                      ('resources/android/engine/ics-openvpn-sources.zip', 'sources_sha256')]:
    assert hashlib.sha256((package / relative).read_bytes()).hexdigest() == metadata[key]
print('PASS engine JobService, persisted-job permission, source manifest and distribution integrity')
