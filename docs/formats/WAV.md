# WAV sounds

Sound effects and music are RIFF/WAVE files with uncompressed PCM. `WavReader` decodes one file into
an immutable `PcmSound`; `engine/audio` plays it (`SoundSystem` for effects, `BackgroundTrack` for
the intro/loop music, `SoftwareMixer` or the host's device for output).

Code:
[`WavReader`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/wav/WavReader.java),
[`PcmSound`](../../engine/formats/src/main/java/dev/bluevista/craftq3/engine/formats/wav/PcmSound.java);
consumers [`SoundSystem`](../../engine/audio/src/main/java/dev/bluevista/craftq3/engine/audio/SoundSystem.java),
[`BackgroundTrack`](../../engine/audio/src/main/java/dev/bluevista/craftq3/engine/audio/BackgroundTrack.java),
[`Pcm`](../../engine/audio/src/main/java/dev/bluevista/craftq3/engine/audio/Pcm.java).
References: Microsoft's
[RIFF](https://learn.microsoft.com/en-us/windows/win32/xaudio2/resource-interchange-file-format--riff-)
and [WAVEFORMATEX](https://learn.microsoft.com/en-us/windows/win32/api/mmeapi/ns-mmeapi-waveformatex)
descriptions.

## Accepted input

The reader follows the original sound loader, which is lenient:

- The file must start with `RIFF` and have `WAVE` at byte 8. The RIFF length field is not trusted:
  chunks are scanned word-aligned (odd lengths padded to even) from byte 12 to the end of the file,
  at most 4,096 chunks, stopping at a negative chunk length.
- The first `fmt ` and the first `data` chunk are used wherever they appear; other chunks are
  skipped.
- Format tag 1 (PCM) only, or `WAVE_FORMAT_EXTENSIBLE` (0xFFFE) whose sub-format is PCM. Compressed
  formats are refused.
- 1 or 2 channels; unsigned 8-bit or signed little-endian 16-bit samples; sample rate 1 to
  1,000,000 Hz. Byte-rate and block-align fields are ignored.
- A data chunk that runs past the end of the file (including a missing final pad byte) or ends in
  a partial sample frame is cut to the complete frames present, with a one-time warning.

Files are limited to 64 MiB. Errors are `WavFormatException` (unchecked); `SoundSystem` then
registers the name as a silent placeholder and warns once, as a missing sound is not fatal.

## Decoded form

`PcmSound` owns its bytes and reports rate, channels, bits, frame count and duration.
`sample16` widens one sample to signed 16-bit and `mono16le` averages stereo to mono without
resampling. `Pcm` converts to interleaved signed 16-bit for the audio layer; rates are never
changed at load time, only the software mixer resamples (linear interpolation) while mixing.

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Tests: `WavReaderTest`.
