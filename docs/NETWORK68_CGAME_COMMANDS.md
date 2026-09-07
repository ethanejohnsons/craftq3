# Native cgame command consumption

The protocol message parser and cgame's command-fetch service have distinct effects. This document records observations of unchanged native client operations, which guide the remote cgame adapter. The observer is a development fixture; it is not shipped or invoked by the game.

## Command and configstring timing

Receiving a reliable `cs` command does not change `CL_GetGameState`. Calling `CL_GetServerCommand` for that sequence applies the update, returns true, and exposes its command arguments. An authored message containing command 1, a snapshot, and command 2 confirms this through `CL_ParseServerMessage`, rather than direct ring setup alone.

An ordinary `cs` takes its value from `Cmd_ArgsFrom(2)`: `cs 100 a b` stores `a b`, and `cs 100` clears the value. The original command text remains visible. Fetching a command does not automatically execute preceding commands. Native fetches are not idempotent: a retained duplicate is processed again, and fetching an earlier retained sequence moves `lastExecutedServerCommand` backward.

The reliable ring retains 64 commands. With latest sequence 65, request 1 is too old: live mode raises `ERR_DROP`, while demo mode returns false. Neither changes the previous arguments or executed sequence. A future request raises `ERR_DROP` in either mode. These are direct helper observations; the observer catches native errors at the call boundary and does not emulate full client-disconnect cleanup.

## Large configstrings

`bcs0` starts a retained assembly; `bcs1` appends; both return false while updating the executed sequence. `bcs2` appends a closing quote, processes the resulting `cs` command, and returns true with the rewritten text and arguments.

Each fragment contributes only `argv[2]`, not the remaining argument string. The initial `bcs0` index token is preserved exactly, including leading zeroes. Later fragment index tokens are ignored. For example:

```text
bcs0 00101 a b
bcs1 999 c d
bcs2 777 e f
```

The final raw text is `cs 00101 "ace"`, and configstring 101 contains `ace`.

The retained buffer includes the closing quote added by `bcs2`. Thus `bcs0 100 a`, followed by `bcs2 100 b` and `bcs2 100 c`, produces final raw bytes `63732031303020226162226322` (`cs 100 "ab"c"`). Native tokenization returns five arguments: `cs`, `100`, `ab`, `c`, and an empty argument. The resulting configstring is `ab c `, including its trailing space (hex `6162206320`). No implicit fragment-state reset should be inferred from the first final fragment.

The assembly limit is `BIG_INFO_STRING`, 8192 bytes, including the rewritten prefix and terminating NUL. Let P be the byte length of `cs <initial-index-token> "` and V the accumulated value length. An intermediate append fits when P + V < 8192. A final append requires P + V + 1 < 8192, accounting for its closing quote. For index `100`, the final value maximum is 8182 bytes; 8183 fits only an intermediate append. The failure is `ERR_DROP: bcs exceeded BIG_INFO_STRING`. Twenty-three boundary fixtures also cover indices `50`, `1023`, and `000000100`.

## Snapshots and retained user commands

`CL_GetSnapshot` writes the `serverCommandSequence` captured when that snapshot was parsed. Commands later in the same packet do not increase the snapshot's value. The authored sequence `[command 1, snapshot 2, command 2]` returns sequence 1 for snapshot 2; the next snapshot returns sequence 2. Retaining and later fetching snapshot 2 preserves its original sequence.

In the observed native implementation, successful `CL_GetSnapshot` **does not write `numServerCommands`**. Three independent output fills, 0x00, 0x33, and 0x7f, survive in that field. A count derived from the preceding snapshot would be invented behavior. The fixture observes this using the declared native `snapshot_t` layout, whose size in this build is 53,772 bytes.

`map_restart` returns true, retains its command text, calls `Con_ClearNotify`, and zeroes the entire 64-entry native user-command ring. It preserves the command number and configstrings, and does not call the fixture's `S_ClearSoundBuffer` callback. With current command number 123, retained requests 60 through 123 subsequently return true with zero command bytes. Request 59 returns false without changing the supplied output; request 124 raises `ERR_DROP`. This describes the engine service, not subsequent cgame effects.

`disconnect` advances the executed command sequence before raising `ERR_SERVERDISCONNECT`. A supplied reason comes from `argv[1]`; absent a reason, the message is `Server disconnected`. No socket, renderer, cgame VM, or full disconnect teardown is started by this observer.

## Evidence and reproduction

The reference is unchanged ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a`, locally available under the ignored `.tools/ioquake3-source` directory. Only public/internal header declarations and exact exported definition signatures were inspected. Native function bodies were neither read nor translated.

- [GetServerCommandOracle.c](../scripts/GetServerCommandOracle.c) supplies authored client state, sentinel buffers, effect counters, and a recoverable error callback.
- [BuildGetServerCommandOracle.py](../scripts/BuildGetServerCommandOracle.py) compiles unchanged `cl_cgame`, `cl_parse`, `cmd`, `q_shared`, `msg`, `huffman`, and `net_chan` units. The command-buffer function definition is renamed so an authored recording callback can observe effects. The build reuses only the existing authored parser-host prefix from `ServerMessageOracle.c`.
- [AuditGetServerCommands.py](../scripts/AuditGetServerCommands.py) executes **5,547 native queries**, including 500 authored fragment chains, 23 capacity cases, parsed-message timing, duplicate and sequence bounds, repeated final fragments, snapshot output sentinels, and restart/error behavior. All assertions pass.

```sh
python3 scripts/BuildGetServerCommandOracle.py
python3 scripts/AuditGetServerCommands.py
```

All fixture strings and wire payloads are authored. The observer reads no original media, opens no network connection, and creates no client window. Its isolated external callbacks deliberately limit claims to these operations. It does not establish systeminfo filesystem/cvar effects, complete error teardown, or whole-engine client cadence.
