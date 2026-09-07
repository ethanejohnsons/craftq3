# Protocol 68 client connection and private native interoperability

`Protocol68Handshake` and `Protocol68ClientSession` now connect the existing wire
codecs to bounded client connection state. `UdpTransport` supplies an explicit,
resolved-peer nonblocking socket. These services are implemented and exercised
against a real private native server. The Minecraft screen now supports local qagame and direct remote cgame
connections through the original menus; see [remote usage and limits](REMOTE_PLAY.md).

## Connection ownership

The handshake requests a challenge with a client nonce and game name. It checks
an echoed nonce and protocol when present, then emits a quoted, adaptively
compressed connect userinfo packet. Protocol, qport and challenge are engine
fields: user-provided copies, including differently cased names, cannot override
them. The constructor reserves space for the longest signed challenge before
networking. Local compression failures are explicit errors. Invalid peer fields,
stale exchanges and unrelated commands do not advance state. Connect replies
must match the challenge when one is supplied. Retries return owned copies of
the same request. The caller owns deadlines and retry scheduling.

The one/two-field legacy response forms are accepted by focused fixtures. The
actual pinned native server emits the nonce and protocol in challenge replies
and the challenge in connect replies. The transport must pin the chosen peer;
`receive(byte[])` does not infer or authenticate a source address. Protocol 68
challenge/XOR handling is legacy protocol interoperability, not cryptographic
authentication. Server print text is inert returned data.

An established client session owns netchan reassembly, message acknowledgements,
legacy XOR key selection, up to 64 outstanding reliable client commands, retained
server command text and 32 snapshots. Unacknowledged client commands retransmit
in sequence. A backlog larger than one message is sent as a fitting prefix;
commands not yet handed to the transport cannot be acknowledged. The retained client key history includes
the last acknowledged key while 64 subsequent commands remain outstanding.

Complete payload decoding and level/reliable application are transactional.
Malformed payloads consume their transport sequence but cannot partially apply
configstrings, command acknowledgements or snapshots. They force the next
movement to request a full snapshot. Reliable gaps, missing key/history entries,
invalid acknowledgements, unsupported services and invalid configstring updates
are explicit rejected results. Duplicate server commands are not delivered
again. Received command text never executes in this layer.

Gamestates replace level baselines, clear snapshot history and retain their
initial command/message metadata. Reliable `cs` and bounded `bcs0/1/2` updates
maintain the wire configstring view. A cgame consumer can select the consumed
`sv_serverid` for outgoing messages through `usePresentedServerId`; a new
gamestate resets that override to its immediate baseline. Each
snapshot retains the reliable sequence at its exact operation in the message;
a later command in that same payload does not alter its tail metadata. Delta
movement is requested only when the acknowledged message is a retained current
snapshot. A command-only acknowledgement therefore requests a full update.

The remote cgame adapter maintains its own processed-command configstrings
so that future wire updates cannot leak into an older rendered snapshot. See
[the integration boundary](REMOTE_CGAME_BOUNDARY.md).

## Native UDP proof

`scripts/BuildNetworkServerOracle.py` compiles the unchanged pinned development
checkout at commit `588393618dbc82e7207c21c6ddecca229944a03a`. Standalone content
validation permits the user's pak0-only installation; the build explicitly
defines `LEGACY_PROTOCOL` and uses the normal configurable `baseq3` and
`Quake3Arena` names. No native implementation source is patched or translated.
Neither this executable nor native modules ship in the mod.

`NativeNetworkServer.py` creates a private temporary home, links only the
source-built public QA QVMs, reads the user-owned pak0 directly in place and binds
IPv4 loopback on an ephemeral port. Master servers are empty; no discovery, downloads or public match is used.
Only the explicitly bound loopback endpoint participates in the audit.
Pure mode and flood protection are disabled for the controlled compatibility
exercise; the latter permits 130 ordinary score requests to test the reliable
rings. The script removes only its own temporary home when done.

`AuditNativeNetworkSession.java` uses the production handshake, UDP transport,
session, movement/snapshot codecs and XOR. It connects to q3dm1, sends ordinary
forward/backward user commands and 130 reliable score requests, and receives
native gamestate and player/entity snapshots. It deliberately discards selected
outgoing/incoming datagrams, requests a full snapshot, and asks the private
server controller for a normal `map q3dm17` transition. The same established
connection receives the new gamestate and continues movement. Finally, a normal
reliable disconnect must reach native `ClientDisconnect` before shutdown.

The initial production session run passed 240 snapshots (237 delta/3 full),
130 acknowledged client commands, 130 received server commands, a fragmented
gamestate and forced full-snapshot recovery. The later loss/map-transition run
also passes; its exact counters and captured packets are written to ignored
`.tools/network-server-oracle/result.json` and `packets.txt`. The inspected 897-test
package passed 232 snapshots (227 delta/5 full), two gamestates, 11 deliberately
dropped outgoing and six dropped incoming datagrams, all 130 client
acknowledgements, 129 newly delivered server commands, and native disconnect.
Its log is `/tmp/craftq3-network-session-native897.log`. Position distance
excludes the map-transition discontinuity. These captures are runtime behavior
observations, not extracted game files.

Twelve focused session tests cover fragment assembly, owned movement, reliable
retransmission/window limits, fitting backlogs, unsent acknowledgements,
snapshot command ordering, atomic rejection, missing-history recovery,
configstring fragments/server IDs, level resets and bounded histories. Eleven
handshake tests cover state, response validation, retries, wire fields, text
bounds and ownership. The separate compression and UDP suites cover their
lower-level contracts.

```sh
python3 scripts/BuildNetworkServerOracle.py
./gradlew :craftq3-core:classes :craftq3-platform:classes
python3 scripts/AuditNativeNetworkSession.py
```

Use Java 25 and the documented unchanged source/public-QVM development checkout.
No engine routine bodies were used as implementation recipes. The session is an
independent composition of existing native-verified codecs and explicit state
invariants. Full Quake networking still needs pure/checksum handling, server-side connection ownership,
download policy and broader protocol/mod coverage.

The final 899-test aggregate retains byte-identical core and platform module jars
from the 897-test native UDP run; only botlib changed for the separate
initial-solid contact correction. It does not invalidate the network evidence.
