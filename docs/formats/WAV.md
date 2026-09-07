# WAV sound assets

`WavReader` reads RIFF/WAVE PCM directly from VFS bytes. It accepts mono/stereo, unsigned 8-bit and little-endian signed 16-bit samples at 1–192 kHz. Declared RIFF and chunk lengths, alignment, byte rate, sample frames and 64 MiB file size are checked before copying. Metadata chunks are skipped using word padding; duplicate format/data chunks and compressed codecs fail clearly. `PcmSound` owns its bytes and exposes sample format, frame count, duration and optional mono conversion for positional sources.

This increment decodes assets only. Playback, channels, entity-following sounds, looping metadata, streaming music and the Minecraft audio adapter remain pending. No Minecraft SoundEvent or platform audio dependency enters the parser.

Four original pak0 mono 8-bit sounds omit the final RIFF pad byte. That specific final-data-chunk case is accepted after checking that every declared sample byte exists. Missing payload bytes and missing padding between chunks remain errors.

The implementation follows Microsoft's [RIFF description](https://learn.microsoft.com/en-us/windows/win32/xaudio2/resource-interchange-file-format--riff-) and [WAVEFORMATEX layout](https://learn.microsoft.com/en-us/windows/win32/api/mmeapi/ns-mmeapi-waveformatex). No engine decoder code was copied.

The optional local corpus check uses user-owned files:

```sh
./gradlew :craftq3-assets:classes
java -cp craftq3-core/build/classes/java/main:craftq3-assets/build/classes/java/main scripts/AuditSounds.java run/craftq3/games
```

Synthetic tests cover PCM signs, stereo downmixing, byte ownership, metadata padding, compressed-format rejection, truncated samples and unsigned chunk overflow.
