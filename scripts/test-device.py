#!/usr/bin/env python3
import argparse, hashlib, os, pathlib, shlex, subprocess, sys, uuid
root=pathlib.Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
suite=parser.add_mutually_exclusive_group()
suite.add_argument('--remaining-parity',action='store_true')
suite.add_argument('--hdr-color-parity',action='store_true')
suite.add_argument('--text',action='store_true')
suite.add_argument('--existing-effects',action='store_true')
suite.add_argument('--effect-audit',action='store_true')
suite.add_argument('--background-effects',action='store_true')
suite.add_argument('--camera-assistance',action='store_true')
suite.add_argument('--microphone',action='store_true')
suite.add_argument('--responsive-layout',action='store_true')
suite.add_argument('--stabilization',action='store_true')
suite.add_argument('--hdr-graphics',action='store_true')
suite.add_argument('--hdr-full',action='store_true')
suite.add_argument('--scene',action='store_true')
suite.add_argument('--restoration',action='store_true')
suite.add_argument('--time-effects',action='store_true')
suite.add_argument('--hdr-pipeline',action='store_true')
suite.add_argument('--audio-editing',action='store_true')
suite.add_argument('--movie-exports',action='store_true')
suite.add_argument('--covers',action='store_true')
suite.add_argument('--originals',action='store_true')
suite.add_argument('--preview',action='store_true')
suite.add_argument('--effect-contracts',action='store_true')
suite.add_argument('--storage',action='store_true')
suite.add_argument('--archives',action='store_true')
suite.add_argument('--storage-ui',action='store_true')
suite.add_argument('--media-editing',action='store_true')
suite.add_argument('--tool-workspaces',action='store_true')
suite.add_argument('--capture-controls',action='store_true')
suite.add_argument('--capture-compatibility',action='store_true')
suite.add_argument('--capture-endurance',action='store_true')
suite.add_argument('--capture-focus',action='store_true')
suite.add_argument('--showcase',action='store_true')
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
parser.add_argument('--apk-directory',type=pathlib.Path)
parser.add_argument('--current-project',action='store_true')
parser.add_argument('--serial',default=os.environ.get('ANDROID_SERIAL'))
args=parser.parse_args()
if args.serial:os.environ['ANDROID_SERIAL']=args.serial
if args.current_project and not args.playback:parser.error('--current-project requires --playback')
(root/'artifacts').mkdir(exist_ok=True)
if args.parity:(root/'artifacts/parity/android').mkdir(parents=True,exist_ok=True)
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Android/Sdk'))
adb=os.environ.get('ADB',str(sdk/'platform-tools/adb'))
def installed(package,path):
 result=subprocess.run([adb,'shell','pm','path',package],text=True,capture_output=True)
 paths=[line.removeprefix('package:') for line in result.stdout.splitlines() if line.startswith('package:')]
 if result.returncode or len(paths)!=1:return False
 remote=subprocess.run([adb,'shell','sha256sum',shlex.quote(paths[0])],text=True,capture_output=True)
 if remote.returncode or not remote.stdout.split():return False
 with path.open('rb') as source:digest=hashlib.file_digest(source,'sha256').hexdigest()
 return remote.stdout.split()[0]==digest
for package,name in [('org.myrec.app','app/build/outputs/apk/debug/app-debug.apk'),
                     ('org.myrec.app.test','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
 path=(args.apk_directory/pathlib.Path(name).name) if args.apk_directory else root/name
 if installed(package,path):print('Already installed:',package,flush=True)
 else:subprocess.run([adb,'install','-r',str(path)],check=True)
for permission in ['android.permission.CAMERA','android.permission.RECORD_AUDIO']:
 subprocess.run([adb,'shell','pm','grant','org.myrec.app',permission],check=True)
def capture_settings():
 result=subprocess.run([adb,'exec-out','run-as','org.myrec.app','cat','shared_prefs/capture.xml'],capture_output=True)
 return hashlib.sha256(result.stdout).hexdigest() if result.returncode==0 else None
original_capture_settings=capture_settings()
subprocess.run([adb,'shell','cmd','statusbar','collapse'],check=True)
command=[adb,'shell','am','instrument','-w']
if args.remaining_parity:command+=['-e','suite','remaining-parity']
if args.hdr_color_parity:command+=['-e','suite','hdr-color-parity']
if args.text:command+=['-e','suite','text']
if args.existing_effects:command+=['-e','suite','existing-effects']
if args.effect_audit:command+=['-e','suite','effect-audit']
if args.background_effects:command+=['-e','suite','background-effects']
if args.camera_assistance:command+=['-e','suite','camera-assistance']
if args.microphone:command+=['-e','suite','microphone']
if args.responsive_layout:command+=['-e','suite','responsive-layout']
if args.stabilization:command+=['-e','suite','stabilization']
if args.hdr_graphics:command+=['-e','suite','hdr-graphics']
if args.hdr_full:command+=['-e','suite','hdr-full']
if args.scene:command+=['-e','suite','scene']
if args.restoration:command+=['-e','suite','restoration']
if args.time_effects:command+=['-e','suite','time-effects']
if args.hdr_pipeline:command+=['-e','suite','hdr-pipeline']
if args.audio_editing:command+=['-e','suite','audio-editing']
if args.movie_exports:command+=['-e','suite','movie-exports']
if args.covers:command+=['-e','suite','covers']
if args.originals:command+=['-e','suite','originals']
if args.preview:command+=['-e','suite','preview']
if args.effect_contracts:command+=['-e','suite','effect-contracts']
if args.storage:command+=['-e','suite','storage']
if args.archives:command+=['-e','suite','archives']
if args.storage_ui:command+=['-e','suite','storage-ui']
if args.media_editing:command+=['-e','suite','media-editing']
if args.tool_workspaces:command+=['-e','suite','tool-workspaces']
if args.capture_controls:command+=['-e','suite','capture-controls']
if args.capture_compatibility:command+=['-e','suite','capture-compatibility']
if args.capture_endurance:command+=['-e','suite','capture-endurance']
if args.capture_focus:command+=['-e','suite','capture-focus']
if args.showcase:command+=['-e','suite','showcase']
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
session=str(uuid.uuid4())
command+=['-e','testSessionId',session,'org.myrec.app.test/org.myrec.app.DeviceTests']
if args.remaining_parity or args.hdr_color_parity or args.text or args.existing_effects or args.effect_audit or args.background_effects or args.responsive_layout or args.camera_assistance or args.originals or args.movie_exports or args.preview:
 subprocess.run([adb,'shell','am','start','-W','-f','0x10008000','-n','org.myrec.app/.MainActivity','--ez','testSession','true','--es','testSessionId',session],check=True,stdout=subprocess.DEVNULL)
process=subprocess.Popen(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
try:
 output,_=process.communicate(timeout=6)
except subprocess.TimeoutExpired:
 if args.remaining_parity or args.hdr_color_parity or args.text or args.existing_effects or args.effect_audit or args.background_effects or args.camera_assistance or args.microphone or args.responsive_layout or args.stabilization or args.media_editing or args.tool_workspaces or args.live_rotation or args.capture_endurance or args.originals or args.covers or args.movie_exports or args.capture_focus or args.capture_compatibility or args.showcase or args.camera_preview or args.capture_controls or args.hdr_full or args.scene or args.restoration or args.time_effects or args.hdr_pipeline or args.audio_editing or args.preview or args.effect_contracts or args.storage_ui or args.references or args.exports or args.studio:
  def shell(*words):return subprocess.run([adb,'shell',*words],text=True,stdout=subprocess.PIPE,check=True).stdout
  awake='mWakefulness=Awake' in shell('dumpsys','power')
  unlocked='showing=false' in shell('dumpsys','window','policy')
  resumed=[line for line in shell('dumpsys','activity','activities').splitlines() if 'resumed' in line.lower() and 'ActivityRecord' in line]
  picker=any(any(name in line for name in ['documentsui','permissioncontroller','ChooserActivity','ResolverActivity']) for line in resumed)
  if process.poll() is None and awake and unlocked and not picker and not any('org.myrec.app/' in line for line in resumed):
   print('Starting isolated test activity from ADB',flush=True)
   shell('am','start','-W','-f','0x10008000','-n','org.myrec.app/.MainActivity',
    '--ez','testSession','true','--es','testSessionId',session)
 output,_=process.communicate()
result=subprocess.CompletedProcess(command,process.returncode,output)
print(result.stdout,flush=True)
restore=subprocess.run([adb,'shell','am','start','-S','-W','-f','0x10008000','-n','org.myrec.app/.MainActivity'],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,text=True)
if restore.returncode:print('Could not return to the normal app:',restore.stderr.strip(),flush=True)
settings_unchanged=capture_settings()==original_capture_settings
if settings_unchanged:print('Normal recording preferences unchanged',flush=True)
else:print('FAIL Normal recording preferences changed during the isolated test',flush=True)
name='capture-endurance-test.txt' if args.capture_endurance else 'originals-test.txt' if args.originals else 'covers-test.txt' if args.covers else 'movie-exports-test.txt' if args.movie_exports else 'capture-focus-test.txt' if args.capture_focus else 'showcase-test.txt' if args.showcase else 'capture-compatibility-test.txt' if args.capture_compatibility else 'hdr-full-test.txt' if args.hdr_full else 'scene-test.txt' if args.scene else 'restoration-test.txt' if args.restoration else 'time-effects-test.txt' if args.time_effects else 'hdr-pipeline-test.txt' if args.hdr_pipeline else 'audio-editing-test.txt' if args.audio_editing else 'preview-test.txt' if args.preview else 'effect-contracts-test.txt' if args.effect_contracts else 'storage-ui-test.txt' if args.storage_ui else 'archives-test.txt' if args.archives else 'storage-test.txt' if args.storage else 'tool-workspaces-test.txt' if args.tool_workspaces else 'media-editing-test.txt' if args.media_editing else 'capture-controls-test.txt' if args.capture_controls else 'live-rotation-test.txt' if args.live_rotation else 'playback-test.txt' if args.playback else 'camera-preview-test.txt' if args.camera_preview else 'references-test.txt' if args.references else 'hdr-scene-test.txt' if args.hdr_scene else 'hdr-color-test.txt' if args.hdr_color else 'new-ui-test.txt' if args.new_ui else 'composition-test.txt' if args.composition else 'camera-gaps-test.txt' if args.camera_gaps else 'gaps-test.txt' if args.gaps else 'timeline-test.txt' if args.timeline else 'orientation-test.txt' if args.orientation else 'speech-test.txt' if args.speech else 'export-test.txt' if args.exports else 'parity-test.txt' if args.parity else 'studio-test.txt' if args.studio else 'device-test.txt'
if args.remaining_parity:name='remaining-parity-test.txt'
elif args.hdr_color_parity:name='hdr-color-parity-test.txt'
elif args.text:name='text-test.txt'
elif args.existing_effects:name='existing-effects-test.txt'
elif args.effect_audit:name='effect-audit-test.txt'
if args.background_effects:name='background-effects-test.txt'
if args.camera_assistance:name='camera-assistance-test.txt'
if args.microphone:name='microphone-test.txt'
if args.responsive_layout:name='responsive-layout-test.txt'
if args.hdr_graphics:name='hdr-graphics-test.txt'
if args.stabilization:name='stabilization-test.txt'
(root/'artifacts'/name).write_text(result.stdout)
if not settings_unchanged or result.returncode or any(text in result.stdout for text in ['FAIL ', 'Process crashed', 'INSTRUMENTATION_FAILED']) or 'PASS ' not in result.stdout:sys.exit(1)
if args.parity:
 subprocess.run([adb,'pull','/sdcard/Android/data/org.myrec.app/files/parity-screens/.',str(root/'artifacts/parity/android')],check=True)
elif not args.remaining_parity and not args.hdr_color_parity and not args.text and not args.existing_effects and not args.effect_audit and not args.background_effects and not args.camera_assistance and not args.microphone and not args.hdr_graphics and not args.responsive_layout and not args.stabilization and not args.capture_endurance and not args.originals and not args.covers and not args.movie_exports and not args.capture_focus and not args.showcase and not args.capture_compatibility and not args.hdr_full and not args.scene and not args.restoration and not args.time_effects and not args.hdr_pipeline and not args.audio_editing and not args.preview and not args.effect_contracts and not args.storage_ui and not args.archives and not args.storage and not args.media_editing and not args.tool_workspaces and not args.capture_controls and not args.live_rotation and not args.playback and not args.camera_preview and not args.references and not args.hdr_scene and not args.hdr_color and not args.new_ui and not args.composition and not args.camera_gaps and not args.gaps and not args.speech and not args.exports and not args.studio and not args.orientation and not args.timeline:
 subprocess.run([adb,'pull','/sdcard/Android/data/org.myrec.app/files/test-screens/.',str(root/'artifacts/screens')],check=True)
 subprocess.run([adb,'pull','/sdcard/Android/data/org.myrec.app/files/test-export.mp4',str(root/'artifacts/test-export.mp4')],check=True)
