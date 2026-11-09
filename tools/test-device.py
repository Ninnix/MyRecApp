#!/usr/bin/env python3
import argparse, os, pathlib, subprocess, sys
root=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
suite=parser.add_mutually_exclusive_group()
suite.add_argument('--capture-controls',action='store_true')
suite.add_argument('--hdr-scene',action='store_true')
suite.add_argument('--hdr-color',action='store_true')
suite.add_argument('--live-rotation',action='store_true')
suite.add_argument('--playback',action='store_true')
suite.add_argument('--camera-preview',action='store_true')
suite.add_argument('--references',action='store_true')
suite.add_argument('--new-ui',action='store_true')
suite.add_argument('--composition',action='store_true')
suite.add_argument('--camera-gaps',action='store_true')
suite.add_argument('--gaps',action='store_true')
suite.add_argument('--speech',action='store_true')
suite.add_argument('--exports',action='store_true')
suite.add_argument('--parity',action='store_true')
suite.add_argument('--studio',action='store_true')
suite.add_argument('--orientation',action='store_true')
suite.add_argument('--timeline',action='store_true')
parser.add_argument('--current-project',action='store_true')
args=parser.parse_args()
if args.current_project and not args.playback:parser.error('--current-project requires --playback')
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
if args.capture_controls:command+=['-e','suite','capture-controls']
if args.hdr_scene:command+=['-e','suite','hdr-scene']
if args.hdr_color:command+=['-e','suite','hdr-color']
if args.live_rotation:command+=['-e','suite','live-rotation']
if args.playback:command+=['-e','suite','playback']
if args.current_project:command+=['-e','currentProject','true']
if args.camera_preview:command+=['-e','suite','camera-preview']
if args.references:command+=['-e','suite','references']
if args.new_ui:command+=['-e','suite','new-ui']
if args.composition:command+=['-e','suite','composition']
if args.camera_gaps:command+=['-e','suite','camera-gaps']
if args.gaps:command+=['-e','suite','gaps']
if args.speech:command+=['-e','suite','speech']
if args.exports:command+=['-e','suite','exports']
if args.parity:command+=['-e','suite','parity']
if args.studio:command+=['-e','suite','studio']
if args.orientation:command+=['-e','suite','orientation']
if args.timeline:command+=['-e','suite','timeline']
command+=['org.yourec.app.test/org.yourec.app.DeviceTests']
result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
subprocess.run([adb,'shell','am','start','-S','-W','-f','0x10008000','-n','org.yourec.app/.MainActivity'],stdout=subprocess.DEVNULL,check=True)
print(result.stdout)
name='capture-controls-test.txt' if args.capture_controls else 'live-rotation-test.txt' if args.live_rotation else 'playback-test.txt' if args.playback else 'camera-preview-test.txt' if args.camera_preview else 'references-test.txt' if args.references else 'hdr-scene-test.txt' if args.hdr_scene else 'hdr-color-test.txt' if args.hdr_color else 'new-ui-test.txt' if args.new_ui else 'composition-test.txt' if args.composition else 'camera-gaps-test.txt' if args.camera_gaps else 'gaps-test.txt' if args.gaps else 'timeline-test.txt' if args.timeline else 'orientation-test.txt' if args.orientation else 'speech-test.txt' if args.speech else 'export-test.txt' if args.exports else 'parity-test.txt' if args.parity else 'studio-test.txt' if args.studio else 'device-test.txt'
(root/'artifacts'/name).write_text(result.stdout)
if result.returncode or any(text in result.stdout for text in ['FAIL ', 'Process crashed', 'INSTRUMENTATION_FAILED']) or 'PASS ' not in result.stdout:sys.exit(1)
if args.parity:
 subprocess.run([adb,'pull','/sdcard/Android/data/org.yourec.app/files/parity-screens/.',str(root/'artifacts/parity/android')],check=True)
elif not args.capture_controls and not args.live_rotation and not args.playback and not args.camera_preview and not args.references and not args.hdr_scene and not args.hdr_color and not args.new_ui and not args.composition and not args.camera_gaps and not args.gaps and not args.speech and not args.exports and not args.studio and not args.orientation and not args.timeline:
 subprocess.run([adb,'pull','/sdcard/Android/data/org.yourec.app/files/test-screens/.',str(root/'artifacts/screens')],check=True)
 subprocess.run([adb,'pull','/sdcard/Android/data/org.yourec.app/files/test-export.mp4',str(root/'artifacts/test-export.mp4')],check=True)
