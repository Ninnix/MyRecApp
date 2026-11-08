# Test fixtures

These files are instrumentation inputs, not production media assets.

* `hdr-colors.mp4`, `hdr-colors.yuv`, `hdr-colors.json`: synthetic BT.2020 HLG patches generated
  by `tools/hdr-fixture.py`, with known nonlinear RGB values for colour checks.
* `fixture.mp4`: generated color bars and audio for decoding, editing and export.
* `person.png`, `person.mp4`: a crop of supplied horizontal reference frame 0752
  and its short encoded derivative, used to check segmentation and range timing.
* `stabilizer.png`, `shaky.mp4`: a supplied-reference crop and a controlled-motion
  derivative for geometric tracking.
* `speech.wav`: `test_speech.wav` from the pinned
  [Qwen ASR samples](https://github.com/antirez/qwen-asr/tree/924694251d9e0f18e5d86bbd06aa3ab5f870002d/samples).

Supplied reference material retains its original provenance. The synthetic
screenshots in the repository show test media rather than personal recordings.
