# Original `play` sound command

Original qagame queues `play sound/player/announce/crash.wav` when Crash enters
the Single Player introduction. The local server and cgame have separate command
buffers, so a cgame-only handler would still leave this cue unhandled.
`Q3Client` now installs a small engine-owned sound-command handler on both
buffers when they are distinct. It uses the existing bounded virtual filesystem,
WAV decoder/cache and borrowed `AudioBackend`.

Every argument is loaded in order; a valid sound issues LOCAL playback for the
local client on channel 6 (`CHAN_LOCAL_SOUND`), with gain and pitch one. The
normal session gain remains controlled by the existing audio backend.
Extensionless WAV names use the existing `.wav` resolution. Empty or missing
sounds do not play; invalid virtual paths and malformed sound files produce a
diagnostic and permit later arguments to run. Command, path and decoded-asset
budgets remain enforced. Other audio codecs are not introduced by this change.

These are host commands, separate from guest command registrations. A viewport
restart retains `play`; closing the client removes its owned registrations from
both buffers. An existing host `play` registration is borrowed and remains in
place. Neither the borrowed filesystem nor audio device is closed by the helper.

## Native observation

`scripts/PlayCommandOracle.c` calls unchanged native `S_Init`, captures its
registered `play` callback through an authored `Cmd_AddCommand`, then invokes it
with authored arguments. An authored silent sound-interface callback table
records registration and local playback. `S_Shutdown` verifies native command
removal. No audio device, graphics context or original asset is opened.

Observed dispatch behavior is:

* Zero arguments after `play` print usage and perform no registration.
* Multiple arguments preserve order and the exact name passed to sound
  registration, with `compressed=false`.
* Each nonzero returned handle starts local playback on channel 6; zero skips
  playback and still processes later names.
* Empty and long strings are forwarded by the command itself; lower-level
  registration owns validity. CraftQ3 retains its bounded virtual-path checks.

`SoundPathOracle.c` separately calls unchanged `S_CodecLoad` with a silent WAV
codec callback. It observes extensionless `sound/feedback/intro_01` becoming
`sound/feedback/intro_01.wav`, while an explicit `.wav` name remains intact.
That development probe builds the WAV-only codec configuration; its other
fallback-name observations are not a claim of support for additional codecs.

The reference is ioquake3 commit
`588393618dbc82e7207c21c6ddecca229944a03a`. Only headers, exported symbols,
callback declarations and native call results were used, including
[`snd_public.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/client/snd_public.h),
[`snd_local.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/client/snd_local.h)
and [`snd_codec.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/client/snd_codec.h).
The original routines are compiled unchanged into ignored development tools;
no routine bodies were inspected, copied or translated into the mod.

```sh
python3 scripts/BuildPlayCommandOracle.py
.tools/play-command-oracle/probe sound/feedback/intro_01 other.wav
CRAFTQ3_ORACLE_SOUND_HANDLE=0 .tools/play-command-oracle/probe missing another
.tools/play-command-oracle/codec-probe sound/feedback/intro_01 explicit.wav
```

Four focused tests exercise ordered cached playback, missing/invalid arguments,
borrowed registration ownership and both guest ABIs across viewport restart and
client close. All 35 client-module tests pass. The original sixty-second
Single Player replay confirms that the Crash cue reaches `AudioBackend` as
LOCAL/entity 0/channel 6, while Crash still moves and attacks. This confirms
request delivery through a CPU recording sink; it does not itself prove audible
playback. Local logs are `/tmp/craftq3-client-play-tests.log`,
`/tmp/craftq3-single-player-game-play.log`, and
`.tools/play-command-oracle/{basic,zero-handle,noargs,codec}.log`.

The final 853-test engine snapshot repeats this encounter with the normal
production bot default, no `bot_enable` override, and an assertion that the
original Crash cue was delivered. Its log is
`/tmp/craftq3-single-player-game-default853.log`.
