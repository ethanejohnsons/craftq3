# Native pure-server verification

The private protocol-68 wire observer establishes the server's `cp` and `vdr` behavior using unchanged ioquake3 and ordinary reliable client commands. All traffic is bound to `127.0.0.1`, master-server addresses are empty, and original game packs are read in place. The observer creates separate temporary packages containing unchanged public QA QVMs and authored data so that cgame, UI, and general-package roles have distinct checksums.

## Provenance

The native reference is official ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a`, with standalone/legacy-protocol configuration. Public declarations and record metadata are in [`qcommon.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/qcommon/qcommon.h), [`client.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/client/client.h), and [`server.h`](https://github.com/ioquake/ioq3/blob/588393618dbc82e7207c21c6ddecca229944a03a/code/server/server.h). No native routine bodies or disassembly were used to derive an implementation.

The server probe sends authored candidate commands through production Java handshake, netchannel, message, and session primitives. Native `ClientBegin` and `ClientDisconnect` log records establish admission and rejection; receipt of a snapshot alone does not establish successful pure verification. The checksum transcript comes from independently called native filesystem operations, whose format and production differential are documented in [NETWORK_PURE_FILESYSTEM.md](NETWORK_PURE_FILESYSTEM.md).

The separate client-operation observer links unchanged `client/cl_main.c` and `qcommon/q_shared.c`. It calls exported `CL_SendPureChecksums(void)` and `CL_ResetPureClientAtServer(void)` with seeded public client metadata. Its authored `CL_AddReliableCommand` replacement records arguments. The build renames only that function's LLVM definition header for interposition; native instruction bodies are neither inspected nor changed. This CPU-only observer does not initialize a renderer, run a VM, or open a socket.

## Gamestate and command format

With `sv_pure=1`, the gamestate's systeminfo contains `sv_paks` and `sv_pakNames`, plus referenced-pack metadata. `sv_paks` contains **normal** pack checksums; it does not contain feed-keyed pure checksums. Original pak0's observed normal checksum is `1566731103`. The gamestate carries a separate signed `checksumFeed`, and systeminfo carries `sv_serverid`.

The direct native client operations emit:

```text
cp <cl.serverId> <FS_ReferencedPakPureChecksums()>
vdr
```

`cp` obtains the native filesystem transcript exactly once, preserves its trailing spaces, and queues an ordinary reliable command (`isDisconnect=false`). Its local 1024-byte buffer limits the emitted text to 1023 bytes. `vdr` queues those three characters with the same ordinary reliable flag and performs no filesystem query. The direct functions emit the same way for the tested pure/nonpure flags, connection states, and demo states; callers control whether and when to invoke them.

The formatting corpus passed **4,000 native operations across 2,000 state cases**, covering connection states 0–9, both pure and demo flags, server IDs including signed extrema, empty/ordinary transcripts, and lengths through 16,000 bytes. This establishes the direct-call formatting contract, not native renderer-restart call timing.

## Server acceptance matrix

All **16 wire cases** passed with separate private servers. Each submitted command was acknowledged. General-list variations recompute the independently observed terminal checksum, except the deliberately wrong-terminal case.

| Submitted candidate | Native outcome |
| --- | --- |
| Native filesystem transcript and current server ID | Admitted |
| No `cp` | Remains unadmitted; snapshots may still arrive |
| Current server ID minus one | Ignored; no admission or disconnect |
| Current server ID plus 100,000 | Admitted |
| Swapped distinct cgame/UI checksums | Disconnected |
| Reversed general-reference order | Admitted |
| Duplicate general-reference checksum | Disconnected |
| Omitted last general-reference checksum | Admitted |
| Empty general-reference list, terminal equal to feed | Admitted |
| Unknown general-reference checksum | Disconnected |
| Incorrect terminal checksum | Disconnected |
| Transcript generated with `checksumFeed XOR 1` | Disconnected |
| Incorrect cgame checksum | Disconnected |
| Incorrect UI checksum | Disconnected |
| `!` in place of `@` | Disconnected |
| Extra final numeric argument | Disconnected |

These are compatibility observations, not a proposal to omit references. A client should send its complete native-format reference transcript and current server ID. The server's ID check is not simple equality. Public server metadata identifies a checksum-feed server-ID boundary; the lifecycle observations below distinguish that boundary from the later fast-restart ID.

## Reset and map lifecycle

The nine-stage lifecycle replay passed with the following observations:

1. A valid `cp` admits the client once.
2. Queueing `vdr` and the valid `cp` together before the next usercmd preserves admission and does not produce another gamestate.
3. Sending `vdr` alone while continuing usercmds causes a new gamestate with the **same** server ID and checksum feed. The client is not disconnected, but it must verify again before re-entering the game.
4. Repeating the valid `cp` re-enters the game.
5. Native `map_restart 0` changes the server ID, retains the checksum feed, and continues the admitted client without an additional gamestate or `cp`.
6. After another `vdr`, the original pre-restart `cp` is still accepted: its ID predates the fast restart, but belongs to the retained feed boundary.
7. Native `map q3dm17` sends a new gamestate with a new server ID and feed. The client requires new verification.
8. The original old-ID/old-feed `cp` is ignored without disconnecting the client.
9. A current-ID transcript generated from the new feed admits the client.

Thus a renderer/content reload should not continue sending movement between its reset and verification commands. The wire fixture proves that batching the pair before a usercmd avoids this reset race. It does not claim to reproduce the full native video-restart caller sequence, and map changes still require fresh content/reference initialization.

## Pre-gamestate snapshot observation

During connection, the native server can send a valid not-active snapshot before its first gamestate. This occurred independently of pure mode. The research wire harness records and permits only the then-current session diagnostic `Snapshot precedes gamestate`, allowing the pure-command experiment to continue. Other wire rejection diagnostics fail the audit. This observer allowance is not a production protocol policy.

`DecodePurePreGamePacket.java` preserves one exact captured datagram and its challenge. Independent primitive decoding yielded reliable acknowledgement 0, message sequence 1, server time 700, snapshot flags 6, a full rather than delta snapshot, one area-mask byte, and one entity. This separates the session-ordering issue from invalid XOR or corrupt payload hypotheses. Production session handling is owned by the client integration work.

## Reproduction and artifacts

The native dedicated engine, unchanged QA QVMs, fixed original pak0 mount, and production Java core/platform classes must already be available. `NativeNetworkServer` retains its default `pure=False`; the optional `pure=True` fixture changes only the native cvar. `NativePureServer` adds packages and removes loose VM symlinks only within its own temporary home. No original pack is extracted or modified.

```sh
python3 scripts/BuildNetworkServerOracle.py
python3 scripts/BuildPureFilesystemOracle.py
python3 scripts/BuildPureClientCommandsOracle.py
python3 scripts/AuditPureClientCommands.py
JAVA_HOME=/path/to/jdk-25 python3 scripts/AuditPureServer.py
```

`AuditPureServer.py native` or `AuditPureServer.py lifecycle` selects a bounded replay. `CRAFTQ3_AUDIT_CLASSPATH` can point to an inspected runtime snapshot; otherwise the harness uses existing core/platform compiled classes. `PureWireProbe.java` runs in Java source mode without changing shared build outputs.

Results are written under `.tools/pure-wire/acceptance-*.json` and `.tools/pure-wire/lifecycle.json`. They retain candidate commands, gamestates, reliable server messages, summaries, and raw observer records. Native server logs live under `.tools/network-server-oracle/pure-check-*.log`. The checkpoint passed all 16 admission cases, all nine lifecycle stages, and all 4,000 direct native command operations.

The scope is protocol-68 pure command behavior with locally available content. Downloading, arbitrary server/mod authentication policy, checksum-collision resistance, and complete native client renderer lifecycle parity are not established by this corpus.

## Actual application recovery from a missing required pack

`AuditPureConnectionFailure.java` executes the real Fabric `QuakeSession` on the CPU, with unchanged public UI/cgame/qagame QVMs and a silent audio sink. Its Python wrapper starts `NativePureServer`, gives the client every server fixture pack **except** `z_qa_cgame.pk3`, and adds a disallowed client-only pack containing the same cgame/UI QVM bytes plus authored data. Matching module bytes in that differently checksummed package must not satisfy the required-pack preflight.

The application audit passed: it reported `Missing server PK3s: baseq3/z_qa_cgame.pk3` after four connecting frames, preserved the same running UI instance and local module origins, rendered ten recovery-menu frames, and started a local game for 60 frames with 180 submitted views. Local disconnect returned to the original menu. The independent native log contained exactly one `ClientConnect`, **zero `ClientBegin`**, and no unpure-response rejection. This establishes recovery through the actual application path before remote cgame initialization, rather than merely recognizing a server-side kick.

```sh
JAVA_HOME=/path/to/jdk-25 python3 scripts/AuditPureConnectionFailure.py
```

The wrapper selects the source-mode application audit through `:craftq3-fabric:auditRemoteApplication -Pq3RemoteAuditFailure=true`, writes `.tools/pure-connection-failure/failure-result.json`, and checks `.tools/network-server-oracle/pure-failure.log`. It does not run the GUI or download missing content. Its temporary client pack fixtures are removed when the private audit exits.
