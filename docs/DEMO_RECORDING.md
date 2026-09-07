# Protocol-68 recording provider

`client.demo.Protocol68DemoRecorder` owns a streaming, bounded protocol-68 demo output. The application owns record/stop commands, player-state eligibility, filenames, storage policy, full-snapshot requests and decrypted message capture. The provider performs no filesystem lookup, socket access, VM invocation, or demo playback.

## API and state

```java
new Protocol68DemoRecorder(OutputStream output, long maxBytes)
start(GameState game, int currentServerSequence, int clientReliableSequence)
boolean accept(DemoRecord decrypted, ServerMessageCodec.Message decoded)
finish()
close()
state()
recordsWritten()
bytesWritten()
```

The constructor owns the stream and requires an explicit total byte limit, including framing and the final eight bytes. It does not write before `start`. `start` serializes the supplied current gamestate, writes one initial record, and enters `WAITING`. It can succeed only once. Encoding and size validation happen before the first output write.

The initial record has sequence `currentServerSequence - 1`. Its payload is a server message whose reliable acknowledgement field is the client's current reliable **sequence**, followed by one gamestate. That gamestate uses the current received server-command sequence, current configstrings, nonzero-number entity baselines, client number and checksum feed. The host must supply that current metadata; the original command baseline retained in an initial network gamestate is not automatically current. Native baseline number zero is omitted even when its other fields are nonzero.

While `WAITING`, command-only and delta-only packets are skipped. A decoded `Frame` with no previous snapshot clears waiting. The **entire same packet** is written, including preceding/following server commands, original unused bits and any trailing bytes. Later accepted packets are written without re-encoding. Later gamestates preserve the current wait state; they do not re-arm an active recording. The host calls `accept` only after successful parsing, with the matching decrypted payload and decoded metadata. Snapshot/message sequence mismatch is rejected before writing.

`finish` writes and flushes one canonical `-1/-1` end marker. Repeated finish calls are harmless; finishing before start writes nothing. `close` finishes an active healthy recording and closes the owned stream once. A recorder never started closes without creating a marker-only file. The public states are `NEW`, `WAITING`, `RECORDING`, `FINISHED`, `FAILED`, and `CLOSED`.

Each record remains bounded at 16 KiB by the existing `DemoRecord`/`DemoWriter` framing. Before every append, the recorder checks the total budget while reserving eight bytes for the end marker. A budget failure writes nothing, leaves the valid prefix and recording state intact, and allows the host to finish that prefix cleanly. An actual stream `IOException` marks the recorder failed. Further accepts/finish fail, and close does not invent a clean trailer after a partial write. Counters describe successfully completed writes; an underlying stream may contain additional partial bytes after an I/O failure.

## Native evidence

The reference is the unchanged official ioquake3 build used by `scripts/DemoPlaybackOracle.c` and `BuildDemoPlaybackOracle.py`. Public headers supply the declarations and state layout; behavioral rules come from authored calls with in-memory input/output. No native routine body, disassembly or implementation recipe is read or translated.

The initial-state observer seeds public client metadata and calls `CL_Record_f`. `CL_ParseSnapshot` separately verifies that a full snapshot clears waiting, while a delta with missing history does not. The packet observer isolates parsing effects to establish that `CL_PacketEvent` checks waiting after parsing and writes the packet that cleared it. Direct `CL_WriteDemoMessage` observations establish header removal and byte preservation. `CL_StopRecord_f` writes the canonical marker, closes the handle, and performs no additional write on repeated stop.

Separate native probes confirm that a nonzero baseline stored at entity index zero is omitted and that `CL_ParseServerMessage` receiving a new gamestate preserves both initially clear and initially set waiting states. Their ignored captures are `.tools/demo-playback-oracle/baseline-zero.log` and `.tools/demo-cgame-oracle/effects.json`. Broader framing/native observations are recorded in [the demo host notes](NETWORK68_DEMO_HOST.md).

## Focused production validation

Seven authored tests pass for initial metadata/baselines, waiting and same-packet command retention, raw trailing bytes, later gamestates, exact total-byte boundaries, stream failures and ownership/state validation. The coordinated run also passed 24 core framing, playback-reader and network-session tests, including decrypted message capture.

`scripts/AuditDemoRecorder.java` compares the actual production recorder to the existing native observer over 32 controlled initial states. It covers current signed reliable/command/feed values, sparse configstrings, baseline-zero omission, pending command suppression, full packets containing commands, and subsequent raw packets/end markers.

```sh
python3 scripts/BuildDemoPlaybackOracle.py
./gradlew :craftq3-client:classes
java -cp craftq3-core/build/classes/java/main:craftq3-client/build/classes/java/main \
  scripts/AuditDemoRecorder.java
```

Use Java 25. Result: **32 initial gamestates with zero meaningful-bit differences; 96 records / 13,030 bytes; all 64 later packets and end markers byte-exact**. Two native initial payloads contained unused trailing padding residues. Production deterministically zeroes those unused bits/bytes; the framing length and every meaningful bit match. Later received payloads are retained byte-for-byte, including their padding. The ignored run log is `/tmp/craftq3-demo-recorder-native.log`.

## Original Demos-menu observation

`scripts/AuditDemosMenu.java` executes original retail or supplied public QA UI bytecode, enters Demos through normal keys, and selects its Play button with ordinary mouse input. An authored callback wrapper records `FS_GETFILELIST` arguments and delegates the existing Q3Ui implementation. The VFS supplies only fixture filenames for this inspection; no demo body or guest memory is fabricated. Other original assets are read directly from the user's mount.

| UI | Requested directory | Requested extension | Output capacity | Display and emitted command |
| --- | --- | --- | ---: | --- |
| Retail API 3 | `demos` | `dm3` | 2,048 | `legacy.dm3` displays `LEGACY`; Play emits `demo LEGACY.dm3` |
| Public QA API 4, protocol 68 | `demos` | `.dm_68` | 32,768 | `proto68.dm_68` displays in full; Play emits `demo proto68.dm_68` |

This proves the retail file-list compatibility boundary; it does not imply that a protocol-68 recording is an original `.dm3` format. File-list aliases and playback selection belong to the application/UI host policy. The recorder always emits protocol-68 message content and does not rename or reinterpret files.

Example observer actions are `m580:445,178` after menu entry: move to Play and click. Pass a game directory, optionally a public QA `ui.qvm` or `-` for retail, then that action string. Captures are `/tmp/craftq3-{retail,modern}-demos-menu.log`. This is a CPU menu/provider checkpoint; actual recording storage and application playback are verified separately during their integration.
