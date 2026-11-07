#!/usr/bin/env python3
import argparse, os, pathlib, subprocess, sys
root=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
suite=parser.add_mutually_exclusive_group()
suite.add_argument('--speech',action='store_true')
suite.add_argument('--exports',action='store_true')
suite.add_argument('--parity',action='store_true')
suite.add_argument('--studio',action='store_true')
suite.add_argument('--orientation',action='store_true')
suite.add_argument('--timeline',action='store_true')
args=parser.parse_args()
(root/'artifacts').mkdir(exist_ok=True)
if args.parity:(root/'artifacts/parity/android').mkdir(parents=True,exist_ok=True)
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Android/Sdk'))
adb=os.environ.get('ADB',str(sdk/'platform-tools/adb'))
for path in ['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
 subprocess.run([adb,'install','-r',str(root/path)],check=True)
for permission in ['android.permission.CAMERA','android.permission.RECORD_AUDIO']:
 subprocess.run([adb,'shell','pm','grant','org.yourec.app',permission],check=True)
subprocess.run([adb,'shell','cmd','statusbar','collapse'],check=True)
command=[adb,'shell','am','instrument','-w']
if args.speech:command+=['-e','suite','speech']
if args.exports:command+=['-e','suite','exports']
if args.parity:command+=['-e','suite','parity']
if args.studio:command+=['-e','suite','studio']
if args.orientation:command+=['-e','suite','orientation']
if args.timeline:command+=['-e','suite','timeline']
command+=['org.yourec.app.test/org.yourec.app.DeviceTests']
result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
print(result.stdout)
name='timeline-test.txt' if args.timeline else 'orientation-test.txt' if args.orientation else 'speech-test.txt' if args.speech else 'export-test.txt' if args.exports else 'parity-test.txt' if args.parity else 'studio-test.txt' if args.studio else 'device-test.txt'
(root/'artifacts'/name).write_text(result.stdout)
if result.returncode or any(text in result.stdout for text in ['FAIL ', 'Process crashed', 'INSTRUMENTATION_FAILED']) or 'PASS ' not in result.stdout:sys.exit(1)
if args.parity:
 subprocess.run([adb,'pull','/sdcard/Android/data/org.yourec.app/files/parity-screens/.',str(root/'artifacts/parity/android')],check=True)
elif not args.speech and not args.exports and not args.studio and not args.orientation and not args.timeline:
 subprocess.run([adb,'pull','/sdcard/Android/data/org.yourec.app/files/test-screens/.',str(root/'artifacts/screens')],check=True)
 subprocess.run([adb,'pull','/sdcard/Android/data/org.yourec.app/files/test-export.mp4',str(root/'artifacts/test-export.mp4')],check=True)
