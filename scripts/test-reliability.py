#!/usr/bin/env python3
"""Run process-termination acceptance against an installed, isolated MyRec APK."""
import argparse
import os
from pathlib import Path
import re
import subprocess
from threading import Timer
from uuid import uuid4


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--package', required=True, help='Installed debug package with an application ID suffix')
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL'))
    parser.add_argument('--adb', default=str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platform-tools/adb'))
    parser.add_argument('--output', type=Path, default=Path('artifacts/reliability'))
    parser.add_argument('--device-checks', action='store_true', help='Also run the camera interruption and pressure checks')
    parser.add_argument('--active-take', action='store_true', help='Run only active-take process termination and recovery')
    args = parser.parse_args()
    if not re.fullmatch(r'org\.myrec\.app\.[\w.]+', args.package):
        parser.error('Use an isolated MyRec package with an application ID suffix')
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb] + (['-s', args.serial] if args.serial else [])
    session = 'reliability-' + str(uuid4())

    def run(phase, log, kill=False):
        command = adb + ['shell', 'am', 'instrument', '-w', '-e', 'suite', 'reliability', '-e', 'reliabilityPhase', phase,
                         '-e', 'testSessionId', session, f'{args.package}.test/org.myrec.app.DeviceTests']
        print(phase, flush=True)
        with log.open('w') as out, subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                                     stdin=subprocess.DEVNULL, text=True) as app:
            timer = Timer(150, app.kill)
            timer.start()
            killed = False
            passed = False
            failed = False
            try:
                for line in app.stdout:
                    out.write(line)
                    out.flush()
                    if kill and not killed and line.strip() == f'READY {phase}':
                        subprocess.run(adb + ['shell', 'am', 'force-stop', args.package], check=True, timeout=15)
                        killed = True
                    elif line.startswith('PASS '):
                        passed = True
                    elif line.startswith(('FAIL ', 'INSTRUMENTATION_FAILED')):
                        failed = True
            finally:
                timer.cancel()
            status = app.wait(timeout=10)
            if not (killed if kill else status == 0 and passed and not failed):
                raise SystemExit(f'{phase} failed (ADB status {status}). See {log}')

    phases = ['save-arm', 'save-check', 'save-check', 'export-arm', 'publish-arm', 'export-check']
    if args.device_checks:
        phases.insert(0, 'exercise')
    if args.active_take:
        phases = ['capture-arm', 'capture-check', 'capture-check']
    for index, phase in enumerate(phases, 1):
        run(phase, args.output / f'{index:02d}-{phase}.log', kill=phase.endswith('-arm'))
    print(f'PASS recovery after {sum(phase.endswith("-arm") for phase in phases)} external force-stops. Logs in {args.output}', flush=True)


if __name__ == '__main__':
    main()
