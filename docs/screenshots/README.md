# Colosseum screenshots

`timeline.webp`, `text-overlay.webp`, and `color-luts.webp` are captures of the
actual MyRec interface on a OnePlus 15. They follow the three editing examples
in the [YouRec README](https://github.com/antirez/YouRecApp/tree/faf1815e82e1f2d5f03034a583363d3c3af464a2#editing-a-video-in-yourec),
using different footage and MyRec's Android interface.

The footage is [Stunning Aerial View of the Roman Colosseum, by Maurice Supple](https://www.pexels.com/video/stunning-aerial-view-of-the-roman-colosseum-31553545/),
used under the [Pexels license](https://www.pexels.com/license/). The showcase
uses an 11.28-second excerpt starting at three seconds, resized from 4K to
1920×1080. An original synthesized ambient score provides the audio shown in
the timeline; it is not audio from the stock clip. The source video and prepared
media stay in the ignored build directory.

The project has three linked picture/sound pairs, cuts at 4.5 and 8.5 seconds,
and a 3.5-second title. The timeline is landscape; Text and Color LUTs are
portrait. The screenshots contain no composited replacement controls or iOS
status bar. WebP conversion preserves the captured pixels.

To reproduce with an authorized connected phone:

```sh
mkdir -p .build/rome-showcase
curl -L --fail https://videos.pexels.com/video-files/31553545/13447079_3840_2160_25fps.mp4 \
  -o .build/rome-showcase/colosseum-original.mp4
python3 scripts/prepare-showcase.py
./scripts/build.sh :app:assembleDebug :app:assembleDebugAndroidTest
adb push .build/rome-showcase/colosseum.mp4 \
  /sdcard/Android/data/org.myrec.app/files/test-inputs/colosseum.mp4
python3 scripts/test-device.py --showcase
adb pull /sdcard/Android/data/org.myrec.app/files/showcase/. artifacts/rome-showcase/
```

Set `ANDROID_SERIAL` when multiple phones are attached. The workflow requires
the matching development signing identity and uses an isolated project store.
It returns to the normal app after capture. Raw PNGs are retained under
`artifacts/rome-showcase/`. The source SHA-256 is
`348c98656593495c283e82301679112b76f817c1a3422b37a7674d0050413b1a`.
