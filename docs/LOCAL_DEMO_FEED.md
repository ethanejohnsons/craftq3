# Local demo feed

`client.demo.LocalDemoFeed` serializes completed local server frames as protocol-68 demo messages. It borrows an initialized `Q3Server` and a connected client; original qagame still determines gameplay, player state, visible entities and server commands. The feed performs no input simulation, VM call, filesystem access or networking.

```java
new LocalDemoFeed(Q3Server server, int client, int checksumFeed)
GameState initialGameState()
int nextSequence()
Optional<Emission> capture()
// Emission(DemoRecord record, ServerMessageCodec.Message message)
```

The host starts `Protocol68DemoRecorder` with `initialGameState()`, `nextSequence()` and local reliable acknowledgement zero. The initial record therefore has sequence zero. A recording starts from current configstrings, client number and the supplied checksum feed. Its local reliable-command sequence begins at zero. Baselines are empty; the first full snapshot sends actual visible entity states against zero baselines. No unavailable native spawn baseline is invented.

`capture()` emits once per distinct completed server frame. The first snapshot is full, and each later snapshot refers to the preceding captured sequence. Snapshot time, server-count flags, player state, visible canonical entity states and area mask all come from public `Q3Server` boundaries. The feed does not reproduce native network packet scheduling, bandwidth policy, snapshot rate or acknowledgements; this is an authored canonical local transport for demo playback.

Configstrings are compared with the preceding capture. Changed values and clears produce complete `cs`/`bcs` commands in ascending index order before transient commands. The feed then includes new broadcast commands and commands addressed to the recorded client. Other clients' commands advance the source-history cursor without creating gaps in the recording's own contiguous reliable sequence. Construction starts at `currentServerCommandSequence()`, so an old transient command is never replayed merely because recording started late. A source cursor falling behind the server's retained history remains an explicit error.

The encoded message, configstring snapshot, source cursor, reliable sequence and snapshot sequence are committed together only after successful encoding. A frame containing more than 64 relevant reliable commands or exceeding the existing 16 KiB message limit fails before feed-state publication. The host can report the failure and finish the preceding valid recording. A fast local restart retains the feed: the original `map_restart` command and changed server-count snapshot flag are serialized normally. A new server instance requires a new feed.

## Native outbound configstring contract

`scripts/ConfigstringSendOracle.c` calls unchanged native `SV_SendConfigstring` with authored values and captures its `SV_SendServerCommand` callback. Public server headers provide types and constants. An exact anchored definition-signature lookup establishes the static callable name; the observer includes the unchanged source in the same translation unit solely to make that call possible. No native routine body, disassembly or implementation recipe is read or translated. The observer does not initialize a server, open a socket, mount files or read original game assets.

Observed text behavior:

| Value length in bytes | Emitted commands |
| ---: | --- |
| 0–999 | `cs index "value"` plus newline |
| 1,000 | `bcs0` with 999 bytes, then `bcs2` with one byte |
| 1,998 | `bcs0` with 999 bytes, then `bcs2` with 999 bytes |
| 1,999 | `bcs0` with 999 bytes, `bcs1` with 999 bytes, `bcs2` with one byte |

The fragment size remains 999 at indices 0, 99 and 1023. Each command carries the same decimal index. Quotes, backslashes, semicolons, newlines, carriage returns, tabs, percent signs and nonzero high bytes remain literal inside the generated quotes. This is the producer text contract; it does not promise that every literal string survives native command tokenization unchanged. The existing protocol message-string writer separately applies its native-observed byte conversion.

`configstringCommands(index, value)` is exposed as the bounded formatter used by the feed. It returns immutable strings and rejects invalid indices, NUL, non-byte characters and values at or above the local 16,000-byte gamestate capacity. Initial protocol gamestates and playback's big-string consumer retain their own tighter validation limits.

## Reproduction

```sh
python3 scripts/BuildConfigstringSendOracle.py
./gradlew :craftq3-client:test --tests '*LocalDemoFeedTest'
java -cp craftq3-core/build/classes/java/main:craftq3-server/build/classes/java/main:craftq3-client/build/classes/java/main \
  scripts/AuditConfigstringCommands.java
```

Use Java 25. The production/native comparison passed **39 values, 108 emitted commands and 86,022 output bytes with zero differences**. It covers chunk boundaries and arbitrary nonzero byte values at three decimal index widths; the ignored log is `/tmp/craftq3-configstring-send-native.log`. The authored unit fixtures use public server calls and independent tiny qagame bytecode to verify current state, full/delta encoding, changed and cleared strings, command filtering, recording after expired old history, failed-frame recovery and fast restart. Actual original-game recording and application playback are separate integration checks.
