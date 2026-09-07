# Direct remote play

Open CraftQ3, press backtick, and enter `connect <address>` in the Quake console.
Hostnames and IPv4 accept an optional port; bracketed IPv6 also accepts a port.
Unbracketed IPv6 uses port 27960. For example, `connect 127.0.0.1:27960` connects
to a separately running local server. DNS resolution runs outside the client
thread; challenge, connect, packet reception and transmission are bounded work
on the host thread.

Direct connections support protocol 68 **non-pure and pure servers** with
matching installed game modules and assets. The server must use the game
already mounted by CraftQ3. Pure connections select eligible installed packs,
apply the server's pack order and checksum feed, and send the native reference
verification command after original cgame and UI initialization. Missing
referenced packs can download to an isolated cache when `cl_allowDownload 1` and
server UDP downloads are enabled. Files are checked before loading, and the
connection resumes automatically after a fresh gamestate. Official game packs
require local installation; automatic game-directory switching and HTTP redirects
remain unsupported ([download support](NETWORK_DOWNLOADS.md)). The original Multiplayer menu now has server discovery, pings, sorting, filters, status and persistent favorites; see [browser support](SERVER_BROWSER.md).

Pak0-only local play remains supported. The native remote audit uses matching
source-built public Q3 1.32 QA modules with original pak0 media. This does not
establish compatibility between arbitrary retail, patch and mod combinations.
See [pack checksums](PK3_CHECKSUMS.md),
[filesystem eligibility and references](NETWORK_PURE_FILESYSTEM.md), and
[native pure verification](NETWORK_PURE_VERIFICATION.md).

The original UI supplies connection and in-game menus. `disconnect` returns to
its main menu; a subsequent `map` command starts the ordinary local game. Escape
cancels a pending connection or opens the in-game menu. Remote simulation
continues while menus are open, focus is lost, or the framebuffer is minimized.
Minimized screen ticks run CPU cgame frames with released input and the last
valid dimensions, so retained server commands, prediction and audio advance
without a GPU submission.

## State and timing

`RemoteConnection` composes the native-verified codecs, adaptive handshake and
pinned nonblocking UDP transport. It owns deadlines, bounded reads/sends,
reliable retransmission and a recent input window. An unsent datagram remains
owned until the socket accepts it. New gamestates clear old movement redundancy.
Its scheduling policy is explicit adapter policy, not a claim of native frame
cadence. See [connection ownership](NETWORK_REMOTE_CONNECTION.md) and
[snapshot ping](NETWORK_SNAPSHOT_PING.md).

`RemoteCgameSource` borrows that connection. It preserves sparse message numbers,
actual gamestate baselines, snapshot command-sequence timing and native-unwritten
snapshot count bytes. Its configstrings advance only when original cgame fetches
reliable commands. It reproduces large-string assembly and retained command
repetition, rejects expired live history, and requires a new source for a new
gamestate. Renderer restarts resynchronize the current configstring baseline.
Both local and remote cgame use the same scene, collision, audio and guest-layout
writer. [The source boundary](REMOTE_CGAME_BOUNDARY.md) and
[native command observations](NETWORK68_CGAME_COMMANDS.md) describe the details.

Systeminfo also changes the outgoing server ID at the consumption boundary.
`Protocol68ClientSession.usePresentedServerId` lets cgame select that ID while
the decoder retains future wire configstrings. A new gamestate applies its ID
immediately. `RemoteSystemInfo` permits updates to SYSTEMINFO, SERVER_CREATED or
USER_CREATED cvars, retains protected-variable checks, creates unknown cvars as
SERVER_CREATED|ROM, and resets cheat variables before applying permitted server
values. Other user settings remain local. Display copies of errors/print text
are bounded and converted to the original UI's byte-string range; raw diagnostics
remain console data and never execute as commands.

A new gamestate rebuilds the content view from already mounted packs, then
recreates cgame, UI, world, material and audio assets under that view. Client-only
overrides excluded by the server cannot remain active through retained asset
caches. Disconnect or connection failure restores the default local view and
menu assets. Archive files themselves are unchanged. A pure-mode or allowlist
change within the same gamestate currently produces an explicit reconnect
error; a new gamestate is required to reload content. See
[consumed systeminfo](NETWORK_SYSTEM_INFO.md).

`RemoteServerClock` follows observed native first-snapshot activation, time nudge,
extrapolation and offset correction. Physical input uses a separate monotonic
clock, so a legitimate server-time correction cannot move key timing backward.
[The clock corpus](REMOTE_SERVER_CLOCK.md) covers 1,005,000 exact native frames.

## CPU application proof

`AuditNativeNetworkCgame` first verified the new source against a private native
server: 356 cgame frames, two maps, two viewport restarts, moving/firing input,
HUD, entities and audio submissions. `AuditRemoteConnectionCgame` then used the
production pump, clock and systeminfo path: 413 frames, 155 snapshots, two maps,
a normal native fast restart, two viewport restarts and zero rejected packets.
The latter submitted 1,214 views, 38,427 quads, 11,812 entities and 41 audio voices.
These are CPU submissions, not a new GPU capture.

`AuditRemoteApplication` runs the actual Fabric `QuakeSession`, without launching
Minecraft or a window. It uses original UI/cgame modules from the unchanged
public QA build and reads the original media directly from pak0. The companion
script creates only a temporary QA pack containing those public modules and a
hard link to the existing original pack; it removes its own temporary mount.
The private server binds only loopback and has no masters configured.

The application audits exercise main-menu startup, invalid-address handling,
DNS/connect, ordinary input, the original remote menu without pausing, native
fast restart and map change, background CPU frames with reliable commands,
disconnect/main-menu return, failed socket-open input-clock recovery, bounded
multiline/non-Latin error display, and local play after leaving the remote server.
Non-pure results are written under ignored `.tools/remote-application`.

`AuditPureRemoteApplication` adds a client-only overriding PK3 that the server
does not allow. The completed pure CPU run passes **235 first-map and 90
second-map frames**, 80 background frames, 975 views, 52,782 quads and 121 voices,
followed by 60 local frames after disconnect. It covers two maps, native fast
restart and the original remote menu. Native admission is verified on both maps;
the overriding pack is filtered during remote play and restored afterward.
Results are written under ignored `.tools/pure-remote-application`.

This is CPU application and submission evidence. The last completed
Minecraft/Vulkan smoke remains the separate **897-test checkpoint** described
in [validation](VALIDATION.md); no new GPU capture is claimed for the pure path.

```sh
python3 scripts/BuildNetworkServerOracle.py
./gradlew :craftq3-client:classes
python3 scripts/AuditRemoteConnectionCgame.py
python3 scripts/AuditRemoteApplication.py
python3 scripts/AuditPureRemoteApplication.py
```

Use Java 25 and the documented source-built QA QVMs. Neither native engine code,
these fixture packs, nor original media are distributed in the mod. No native
engine routine bodies were read or used as implementation recipes. The remaining
server hosting, legacy demo and broader gameplay compatibility work precedes Minecraft
interoperability. Downloading and automatic mod switching also remain outside
this direct-client milestone.
