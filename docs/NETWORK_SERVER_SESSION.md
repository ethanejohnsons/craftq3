# Hosted protocol-68 sessions

`Protocol68ServerSession` owns an established server-side wire connection: netchan
fragmentation, legacy XOR keys, reliable retransmission/acknowledgements, current
level metadata, and bounded snapshot history. It has no socket or VM and does not
execute network command text. The Fabric application now accepts remote players through `Protocol68Host`
and an explicitly bound `DatagramListener`.

The connection emits a full initial gamestate and snapshots based on the frame
actually acknowledged by the client. A lost outgoing packet does not lose its
reliable commands. An absent, future or expired snapshot acknowledgement causes
a full snapshot. Snapshot history is limited to 32 message numbers; reliable
commands retain the acknowledged key and at most 64 pending values. The caller
must drain each queued message's fragments before queuing another one.

Incoming commands are de-duplicated by their reliable sequence. A gap is rejected;
malformed input does not partially publish commands or movement. User commands
older than the last accepted timestamp, duplicated timestamps, and timestamps
after the final command in the decoded batch are omitted. Incoming data and
returned canonical user commands have owned byte buffers. A new gamestate clears
snapshot/movement history while retaining connection-level reliable numbering.
Packets for an obsolete server ID produce an explicit `WRONG_GAME` result.

`Protocol68Host` supplies peer/challenge admission, connectionless info/status,
timeouts, pure checks, userinfo updates and original-qagame dispatch. Hosted UDP downloads now serve referenced non-official PK3s when enabled;
the Fabric client now verifies and caches missing packs before resuming the connection. Snapshot frequency honors `snaps`; per-peer byte pacing honors `rate` and
server min/max bounds, including fragmented messages. These policies remain outside the codec.

## Original game admission

`Q3Server.connectPending` reserves a human slot and calls original GAME_CLIENT_CONNECT.
`begin` supplies the first user command and calls GAME_CLIENT_BEGIN only after the
host determines that the client has loaded the level. Pending clients cannot run
GAME_CLIENT_THINK. `connect` retains the immediate local-player path. Fast restart
preserves which humans have entered, and `disconnect` releases both connection
and entry state. Bots retain their original qagame-owned admission path.

The direct original-QVM audit runs both retail and public source-built 1.32 game
modules with original pak0 assets read through the ZIP filesystem. It checks a
pending slot, rejected premature movement, first-command entry, a fast restart
with one active and one pending player, 60 frames with two moving players and
per-player snapshots, and disconnect/slot reuse. Both profiles pass. Movement
is measured in the horizontal plane, with maximum displacements of about
295/354 Quake units for retail and 388/355 for 1.32 in the recorded run.

Repeat with `scripts/AuditServerAdmission.java`, a classpath containing compiled
engine modules, the games directory, and optionally the source-built qagame.qvm
path. The script launches no Minecraft window and opens no network socket.

## Independent native observations and limits

`scripts/BuildServerSessionOracle.py` compiles unchanged ioquake3
`SV_ExecuteClientMessage` and its message/usercmd helpers. Exact definition headers
are renamed for authored callbacks that observe command dispatch, client entry,
client thinking, disconnect and gamestate requests. No native routine body is
inspected or translated into the Java implementation. The same pinned reference
commit and separate development-only oracle policy as the existing network
checks apply.

`scripts/AuditServerSessionPolicy.java` records 43 bounded native cases in
`.tools/server-session-oracle/policy.log`: message/reliable acknowledgements,
server IDs, connected/primed/active states, repeated/gapped client commands,
movement timestamps and pure admission callbacks. Observed behavior in this isolated native build includes:

- Negative message acknowledgements are dropped; nonnegative future values can
  be stored but do not establish a usable delta base.
- Reliable acknowledgements must exceed `reliableSequence - 64` and cannot exceed
  the current reliable sequence.
- Repeated client commands do not dispatch twice; a gap drops the client.
- A primed client enters on its first movement command. Subsequent newer commands
  in that batch dispatch to client thinking.
- Pure admission is separate from packet parsing and must precede normal play.

This observer is a bounded policy transcript, not a full native-client hosting
interop test. Its VM/host callbacks are fixtures. It also exposes intentional Java
host protections: no acknowledgement of commands whose complete outgoing message
has not been drained, monotonic retained reliable acknowledgements, and atomic
rejection of a malformed command batch. Native command dispatch can execute an
earlier valid command before discovering a later gap; CraftQ3 publishes none of
that malformed batch. No native equivalence is claimed for those error paths.

Seven core tests cover fragmented startup and both XOR directions, packet loss and
acknowledged deltas, command/movement deduplication, malformed late gaps, level
changes, expired/future delta bases, and unsent/expired reliable acknowledgements.
UDP listening and original-menu server creation now have application coverage
below. The native established-client packet operations now have coverage below; a
complete native client application remains unverified.


## Hosting application checkpoint

Use the original Multiplayer → Create Server → Next → Fight flow, or enter
`map q3dm1` in the Quake console. Normal multiplayer maps listen on
`net_ip` (default `0.0.0.0`) and `net_port` (default `27960`). Set these before
starting the match. `net_enabled 0` disables listening; `spmap` remains local.
`set dedicated 1; map q3dm1` runs qagame without a local cgame/player. This mode
runs inside the Fabric client; it is not a standalone headless launcher.

The listener survives map replacement. Remote players load a fresh gamestate
and re-enter original qagame; fast restart retains reliable channels. A match
with remote players continues behind menus and during background frames.
Disconnecting the host sends a final snapshot carrying the reliable disconnect,
so remote cgame consumes it and returns to the original menu.

`AuditHostedApplication.py` runs both original retail and public source-built
1.32 UI/game profiles against original read-only pak0 assets. Each test uses
actual host and remote Fabric CPU sessions, starts through the original Create
Server menu, joins a pure match, restarts, changes q3dm1 → q3dm17 on the same
port, advances behind menus/background frames, disconnects/rejoins in dedicated
mode, and checks shutdown recovery. Audit-only settings force ephemeral IPv4
loopback and suppress unrelated discovery requests. No public network traffic
or Minecraft window is involved.

`AuditHostedWire.java` runs two real UDP clients against original retail qagame,
with movement, pure admission, 26 deliberately lost outgoing packets, continued
snapshots and client disconnect. This is Java-to-Java transport coverage with
original gameplay, not independent native-client interoperability proof.

Six additional unit tests cover multi-peer UDP ownership/source addresses,
explicit IPv6 loopback and maximum packets, invalid/closed listener behavior,
challenge retry/source/expiry, discovery rate-limit recovery, and malformed
pre-admission traffic. Successful gameplay admission is covered by the original
QVM application audits rather than the inert unit-test QVM.

Remaining hosting work includes complete native client application coverage,
HTTP downloads, administrative services and master registration. Measured
status/player ping and byte pacing are covered below. Pure hosting
currently uses checksum feed zero; randomized feeds and broader pure lifecycle
coverage remain ahead. Same-family IPv6 UDP is unit tested; full IPv6 hosted
application and dual-stack behavior have not been verified. No new Vulkan smoke
capture is claimed for this checkpoint.


## Native pure validation and established-client exchange

The unchanged native `SV_VerifyPaks_f` now has an authored fixture exposing only
its definition linkage and substituting filesystem/drop/snapshot boundaries.
`BuildPureServerOracle.py` never displays or translates native routine bodies.
`AuditPureServerPolicy.py` compares 585 cases: pure on/off, old/current/later
server IDs, empty/multiple references, module mismatches, duplicate/foreign
packs, altered checksum XOR, four signed checksum feeds, and malformed commands.
584 agree. One explicit difference is retained: the native parser interprets
`bogus` as zero while CraftQ3 rejects that nondecimal checksum. Comparison JSON
and the native transcript are in `.tools/pure-server-oracle/`.

The probe exposed a real stale-response bug. `PureClientPolicy` now ignores a
checksum response whose ID predates the current checksum-feed generation,
preserving the peer's existing admission state. Non-pure servers also ignore
these responses. An accepted later ID has no upper bound in the observed native
policy. Current tampered responses still drop the peer. Three unit tests cover
these boundaries independently of filesystem and socket setup.

`BuildHostedNativeClientOracle.py` links unchanged native `CL_WritePacket`,
client netchan/XOR operations, `CL_ParseServerMessage`, and
`CL_GetServerCommand`. Authored callbacks supply filesystem/download readiness,
frame input and reliable-command submission. `AuditHostedNativeClient.py` runs
this established client over real ephemeral IPv4 loopback UDP against original
retail and public source-built modern qagame in the inspected packaged engine.
Each profile passes two map gamestates, 166 snapshots (103 on the first map),
14 lost server packets, movement, fast restart, q3dm1 → q3dm17 replacement and
an obsolete pure response. Maximum first-map displacement is about 335.4 retail
and 413.3 modern Quake units in the recorded run.

This test also exposed premature resynchronization on fast restart. The host
now allows its new systeminfo to reach the client before interpreting the old
server ID as a stalled load. The native client receives no replacement gamestate
for fast restart; full map replacement still sends one.

The handshake controller and filesystem/frame callbacks are authored fixtures;
this is independent native established-wire interoperability evidence, not a
complete ioquake3 application/UI/rendering test. Original cgame/UI remains
covered by the separate Fabric host/remote CPU application audits. No native
engine code or original assets are bundled, and the original PK3 remains a
read-only ZIP. No public sockets, masters or Minecraft windows are used.


## Hosted bandwidth and ping

`rate` defaults to 3000 bytes/second and is bounded to 1000–90000. A live userinfo
change takes effect without reconnecting. `sv_minRate` / `sv_maxRate` default to
zero (no override); positive values below 1000 are normalized to 1000. The
observed native order applies the maximum first, then the minimum. `snaps`
controls the requested snapshot interval, bounded by `sv_fps`; an absent value
uses the observed 50 ms default.

Each peer has at most one datagram queued for transport. Its next fragment or
message becomes eligible after the socket accepts the previous datagram, using
the actual byte count plus 28-byte IPv4 or 48-byte IPv6 UDP/IP overhead. Fractional
timescale behavior matches the observed integer conversion of the scaled rate.
An inactive connection does not accumulate burst credit. A slow peer does not
block another peer's snapshots. Delayed snapshots carry `SNAPFLAG_RATE_DELAYED`.
The existing game simulation still advances in 50 ms steps; changing `sv_fps`
here does not change simulation frequency.

Ping tracks the first transmitted fragment and only accepts acknowledgements
for known, completely transmitted messages. Duplicate, unsent, expired or
backwards-clock acknowledgements cannot rewrite samples. The mean uses a
32-message sequence ring, capped at 999 ms. Pending/unsampled humans report 999;
bots and the in-process local player report zero. Measured ping is written into
both original player-state ABIs, so qagame scoreboards can consume it, and is
included in `getstatus`. Retail player configstrings may omit their initial
separator; status now handles that representation instead of dropping the reply.

`BuildHostTimingOracle.py` observes unchanged native `SV_RateMsec`,
`SV_CalcPings` and `SV_UserinfoChanged`, with definition-only linkage and authored
clock/entity/cvar/address callbacks. `AuditHostTiming.py` matches 2,400 cases for
rate bounds, packet size/family, elapsed time, positive timescales, ordinary
userinfo and acknowledged-frame averages. Four unit tests cover independent
sequence/fragment guards, averages, pacing and live rate/default bounds.

`AuditHostedTiming.py` runs both original gameplay ABIs through private UDP with
96 ms injected one-way delay, deliberate packet loss, a 1000 → 25000 live rate
change and a second 25000-rate peer. Every scheduled outgoing packet is checked
against its current byte budget. The slow phase yields 43 retail / 47 modern
snapshots; both gain another 68 after the rate increase. Final measured pings are
128 ms retail and 155/128 ms modern, including snapshot transfer/assembly delay.
Player state and status reply fields pass, as does disconnect. Logs are under
`.tools/hosted-timing/{retail,modern}/audit.log`.

Limits: the oracle tests ordinary unicast userinfo with LAN forcing disabled;
CraftQ3 has no native `sv_lanForceRate` override yet. It rejects malformed decimal
fields rather than applying every native numeric-prefix conversion. Its clock
guard omits negative-duration samples that an artificial native struct can
represent. A scaled rate is bounded to at least one to avoid division by zero.
Transport wakeup granularity may underfill a configured rate; this is an upper
budget, not a throughput guarantee. Closing connections bypass pacing to send
final disconnects. Full native application, HTTP downloads, master/admin services and
both Minecraft crossover modes remain ahead.

Hosted transfer details, server settings and client-installation limits are in
[protocol-68 downloads](NETWORK_DOWNLOADS.md).
