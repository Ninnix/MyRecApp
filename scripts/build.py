#!/usr/bin/env python3
"""Build without a Gradle daemon; only the installed JDK, SDK and Kotlin cache are used."""
import os, pathlib, shutil, subprocess, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
os.chdir(ROOT)
BUILD = ROOT / '.build' / 'manual'
SDK = pathlib.Path(os.environ.get('ANDROID_HOME', pathlib.Path.home() / 'Android/Sdk'))
JDK = pathlib.Path(os.environ.get('JAVA_HOME', pathlib.Path.home() / '.local/opt/android-studio/jbr'))
CACHE = pathlib.Path.home() / '.gradle/caches/modules-2/files-2.1'
TOOLS = SDK / 'build-tools/36.0.0'
ANDROID = SDK / 'platforms/android-37.0/android.jar'

def run(*args):
    subprocess.run([str(x) for x in args], check=True)

def jar(group, name, version='*'):
    found = sorted((CACHE / group / name).glob(version + '/*/*.jar'))
    if not found:
        raise SystemExit(f'Missing installed compiler component: {name}')
    return found[-1]

STDLIB = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.3.20')
ANNOTATIONS = jar('org.jetbrains', 'annotations', '23.0.0')
COMPILER = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.3.20'), STDLIB,
            jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.3.20'), ANNOTATIONS,
            jar('org.jetbrains.kotlin', 'kotlin-reflect'), jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm')]

def kotlin(sources, output, extra=()):
    output.mkdir(parents=True, exist_ok=True)
    run(JDK / 'bin/java', '-Xmx3g', '-cp', os.pathsep.join(map(str, COMPILER)),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
        '-jvm-target', '17', '-module-name', 'app', '-classpath', os.pathsep.join(map(str, [ANDROID, STDLIB, ANNOTATIONS, *extra])),
        '-d', output, *sources)

def native():
    ndk = pathlib.Path(os.environ.get('ANDROID_NDK_HOME', SDK / 'ndk/28.2.13676358'))
    if not (ndk / 'toolchains').exists():
        properties = ROOT / 'local.properties'
        paths = [line.removeprefix('ndk.dir=') for line in properties.read_text().splitlines() if line.startswith('ndk.dir=')] if properties.exists() else []
        if not paths: raise SystemExit('Set ANDROID_NDK_HOME to the installed NDK')
        ndk = pathlib.Path(paths[0])
    clang = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang'
    lib = BUILD / 'lib/arm64-v8a'
    lib.mkdir(parents=True, exist_ok=True)
    run(clang, '-std=c11', '-O3', '-Wall', '-Wextra', '-Werror', '-fPIC', '-shared',
        '-I' + str(ROOT / '.build/runtime-sources/onnxruntime-current'),
        '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
        *sorted(ROOT.glob('app/src/main/c/*.c')), '-o', lib / 'libmyrec.so',
        ROOT / '.build/runtime-sources/onnxruntime-current/libonnxruntime.so',
        '-landroid', '-ljnigraphics', '-llog', '-lm', '-ldl')
    shutil.copyfile(ROOT / '.build/runtime-sources/onnxruntime-current/libonnxruntime.so', lib / 'libonnxruntime.so')
    rn = ROOT / '.build/runtime-sources/rnnoise-0.1'
    if rn.exists():
        run(clang, '-std=c11', '-O3', '-fPIC', '-shared', '-DRNNOISE_BUILD', '-D__ANDROID__',
            '-Wl,-z,max-page-size=16384', '-I' + str(rn / 'include'), '-I' + str(rn / 'src'),
            *[rn / ('src/' + s + '.c') for s in ['denoise', 'rnn', 'rnn_data', 'pitch', 'kiss_fft', 'celt_lpc']],
            '-o', lib / 'librnnoise.so', '-lm')

    qwen = ROOT / '.build/runtime-sources/qwen-asr-main'
    if qwen.exists():
        sources = ['qwen_asr', 'qwen_asr_kernels', 'qwen_asr_kernels_generic', 'qwen_asr_kernels_neon',
                   'qwen_asr_kernels_avx', 'qwen_asr_audio', 'qwen_asr_encoder', 'qwen_asr_decoder',
                   'qwen_asr_tokenizer', 'qwen_asr_safetensors']
        run(clang, '-std=c11', '-D_GNU_SOURCE', '-O3', '-fPIC', '-shared', '-ffast-math',
            '-Wl,-z,max-page-size=16384', *[qwen / (s + '.c') for s in sources],
            '-o', lib / 'libqwen_asr.so', '-lm')


def build():
    BUILD.mkdir(parents=True, exist_ok=True)
    compiled = BUILD / 'resources.zip'
    run(TOOLS / 'aapt2', 'compile', '--dir', ROOT / 'app/src/main/res', '-o', compiled)
    apk = BUILD / 'resources.apk'
    manifest = BUILD / 'AndroidManifest.xml'
    source = (ROOT / 'app/src/main/AndroidManifest.xml').read_text().replace('<manifest ', '<manifest package="org.myrec.app" ', 1).replace('<application ', '<application android:debuggable="true" ', 1)
    manifest.write_text(source)
    run(TOOLS / 'aapt2', 'link', '-o', apk, '-I', ANDROID, '--manifest', manifest,
        '-A', ROOT / 'app/src/main/assets',
        '--min-sdk-version', '28', '--target-sdk-version', '35', '--version-code', '5', '--version-name', '0.1.4', compiled)
    classes = BUILD / 'classes'
    if classes.exists(): shutil.rmtree(classes)
    kotlin(sorted(ROOT.glob('app/src/main/java/**/*.kt')), classes)
    native()
    bytecode = BUILD / 'classes.jar'
    with zipfile.ZipFile(bytecode, 'w') as z:
        for path in classes.rglob('*'):
            if path.is_file(): z.write(path, path.relative_to(classes))
    dex = BUILD / 'dex'
    dex.mkdir(exist_ok=True)
    run(JDK / 'bin/java', '-Xmx2g', '-cp', TOOLS / 'lib/d8.jar', 'com.android.tools.r8.D8',
        '--min-api', '28', '--lib', ANDROID, '--output', dex, bytecode, STDLIB)
    unsigned = BUILD / 'unsigned.apk'
    shutil.copyfile(apk, unsigned)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
        for path in dex.glob('*.dex'): z.write(path, path.name)
        for name in ('myrec', 'onnxruntime', 'rnnoise', 'qwen_asr'):
            path = BUILD / 'lib/arm64-v8a' / ('lib' + name + '.so')
            if path.exists(): z.write(path, path.relative_to(BUILD))
    aligned = BUILD / 'aligned.apk'
    run(TOOLS / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned)
    key = ROOT / '.build/debug.keystore'
    if not key.exists():
        run(JDK / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-dname', 'CN=Android Debug,O=Android,C=US', '-keyalg', 'RSA', '-validity', '10000')
    out = BUILD / 'myrec-debug.apk'
    run(JDK / 'bin/java', '-jar', TOOLS / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-pass', 'pass:android',
        '--out', out, aligned)
    run(JDK / 'bin/java', '-jar', TOOLS / 'lib/apksigner.jar', 'verify', out)
    print(out)

if __name__ == '__main__':
    build()
