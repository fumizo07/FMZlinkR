# FMZlinkR notices

FMZlinkR is derived from ShizuCallRecorder by kitsumed (Med) and remains licensed under GNU GPL-3.0-or-later with the applicable additional Section 7 terms in `LICENSE`.

The Rakuten Link VoIP capture path incorporates and adapts technical work from CallVault (`madkongo/CallVault`), including its `USAGE_VOICE_COMMUNICATION` AudioPolicy/AudioMix capture approach, VoIP audio-owner identification, far-party capture, `MIC` near-party capture, PCM mixing, microphone re-take behavior, and encoder/muxer finalization design.

CallVault copyright notices are retained in the adapted source files.

FMZlinkR intentionally does **not** import or use CallVault's embedded-ADB server, independent ADB daemon management, wireless-debugging control, `adbd` restart logic, ADB TCP-port management, or daemon lifecycle management. FMZlinkR also does not start, stop, or restart the Shizuku server.
