"""Check process-loss recovery on a dedicated Android emulator. All route data is simulated."""
import argparse
from contextlib import closing
import re
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', default='artifacts/tracking-recovery')
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Use a dedicated emulator. This check clears app data.')
out = Path(args.output).resolve()
out.mkdir(parents=True, exist_ok=True)
package = 'com.example.cvkegps'

def adb(*command, binary=False):
    result = subprocess.check_output([args.adb, '-s', args.serial, *command])
    return result if binary else result.decode('utf-8', errors='replace').strip()

def shell(*command):
    return adb('shell', *command)

def nodes():
    shell('uiautomator', 'dump', '/sdcard/tracking-check.xml')
    return ET.fromstring(shell('cat', '/sdcard/tracking-check.xml')).iter('node')

def click(text):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        for node in nodes():
            if node.get('text') == text or node.get('content-desc') == text:
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                time.sleep(.6)
                return
        time.sleep(.3)
    raise AssertionError(f'Control not found: {text}')

def check_start_enabled(expected):
    shell('uiautomator', 'dump', '/sdcard/tracking-check.xml')
    tree = ET.fromstring(shell('cat', '/sdcard/tracking-check.xml'))
    parents = {child: parent for parent in tree.iter() for child in parent}
    for node in tree.iter('node'):
        if node.get('text') != 'Start trip':
            continue
        while node is not None:
            if node.get('clickable') == 'true':
                assert (node.get('enabled') == 'true') == expected, node.attrib
                return
            node = parents.get(node)
    raise AssertionError('Start trip button is not visible')

def snapshot_db():
    for name in ('trips.db', 'trips.db-wal', 'trips.db-shm'):
        try:
            data = adb('exec-out', 'run-as', package, 'cat', 'databases/'+name, binary=True)
        except subprocess.CalledProcessError:
            continue
        (out/name).write_bytes(data)
    return sqlite3.connect(out/'trips.db')

def saved_counts():
    with closing(snapshot_db()) as db:
        row = db.execute('select status, (select count(*) from points), (select count(*) from segments) from trips').fetchone()
    return row

def deliver(offset):
    for step in range(3):
        shell('cmd', 'location', 'providers', 'set-test-provider-location', 'gps',
              '--location', f'49.2827,{-123.1207+(offset+step)*.0002}', '--accuracy', '3')
        time.sleep(1.2)

original_location = shell('cmd', 'location', 'is-location-enabled')
original_mock = shell('appops', 'get', 'com.android.shell', 'android:mock_location')
mock_match = re.search(r'MOCK_LOCATION: (\w+)', original_mock)
mock_mode = mock_match.group(1) if mock_match else 'default'
try:
    shell('am', 'force-stop', package)
    shell('pm', 'clear', package)
    shell('cmd', 'location', 'set-location-enabled', 'true')
    shell('appops', 'set', 'com.android.shell', 'android:mock_location', 'allow')
    shell('cmd', 'location', 'providers', 'add-test-provider', 'gps', '--requiresSatellite')
    shell('cmd', 'location', 'providers', 'set-test-provider-enabled', 'gps', 'true')
    for permission in ('ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION'):
        shell('pm', 'grant', package, 'android.permission.'+permission)
    shell('input', 'keyevent', 'KEYCODE_WAKEUP')
    shell('wm', 'dismiss-keyguard')
    shell('am', 'start', '-W', '-n', package+'/.MainActivity')
    click('Start trip')
    check_start_enabled(False)
    shell('pm', 'grant', package, 'android.permission.POST_NOTIFICATIONS')
    shell('input', 'keyevent', 'KEYCODE_HOME')
    shell('am', 'start', '-W', '-n', package+'/.MainActivity')
    check_start_enabled(True)
    click('Start trip')
    deliver(0)
    before = saved_counts()
    assert before and before[0] == 'Recording' and before[1] >= 2, before
    shell('am', 'force-stop', package)
    shell('am', 'start', '-W', '-n', package+'/.MainActivity')
    click('Trips')
    after = saved_counts()
    assert after[0] == 'Interrupted' and after[1] == before[1], (before, after)
    assert not any(package in line and 'Request[' in line for line in
                   shell('dumpsys', 'location').split('Historical Aggregate Location Provider Data')[0].splitlines())
    click('Interrupted')
    # The recovery controls follow the summary and route map.
    for _ in range(4):
        shell('input', 'swipe', '500', '1700', '500', '500', '300')
    click('Resume trip')
    deliver(10)
    for _ in range(4):
        shell('input', 'swipe', '500', '500', '500', '1700', '300')
    click('Stop and save')
    result = saved_counts()
    assert result[0] == 'Finished' and result[1] > before[1] and result[2] == 2, result
    (out/'result.txt').write_text(f'PASS: Notification denial blocked Start; permission recovery enabled it. {before[1]} saved points survived force-stop; {result[1]} points in {result[2]} segments after resume.\n', encoding='utf-8')
    print((out/'result.txt').read_text(encoding='utf-8').strip())
finally:
    shell('am', 'force-stop', package)
    shell('cmd', 'location', 'providers', 'remove-test-provider', 'gps')
    shell('appops', 'set', 'com.android.shell', 'android:mock_location', mock_mode)
    shell('cmd', 'location', 'set-location-enabled', original_location)
    shell('rm', '-f', '/sdcard/tracking-check.xml')
