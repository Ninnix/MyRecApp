#!/usr/bin/env python3
import os, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parent.parent
sdk=pathlib.Path(os.environ.get('ANDROID_HOME',pathlib.Path.home()/'Android/Sdk'))
adb=os.environ.get('ADB',str(sdk/'platform-tools/adb'))
source=root/'.build/models/qwen3-asr-0.6b'
names=['config.json','generation_config.json','vocab.json','merges.txt','model.safetensors']
assert all((source/name).exists() for name in names),'Run tools/prepare-model.py first'
destination='/sdcard/Android/data/org.myrec.app/files/models/qwen3-asr-0.6b'
subprocess.run([adb,'shell','mkdir','-p',destination],check=True)
subprocess.run([adb,'push',*[str(source/name) for name in names],destination+'/'],check=True)
