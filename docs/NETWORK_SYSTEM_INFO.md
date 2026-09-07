# Consumed remote systeminfo

An authored observer calls unchanged `CL_SystemInfoChanged` from pinned native
commit `588393618dbc82e7207c21c6ddecca229944a03a`, with external cvar/filesystem
callbacks recording requested effects. Public headers and the exact exported
signature are the only implementation metadata inspected.

The 16,389-query corpus covers every combination of the low 14 cvar flag bits,
unknown cvars, demo mode, cheat reset and invalid game-directory handling.
Native requests a safe update when SYSTEMINFO, USER_CREATED or SERVER_CREATED
is present; otherwise it warns. Unknown cvars are registered with flags 2112
(SERVER_CREATED|ROM). A missing/zero sv_cheats requests cheat reset **before**
server cvar updates. Demo mode updates the server ID but bypasses the recorded
external effects. Native passes loaded/referenced pack checksum/name strings
to filesystem callbacks; those callbacks are recorded, not implemented by the
observer. The existing protected-cvar path in CraftQ3 independently enforces the
public PROTECTED flag contract.

A separate `CL_GetServerCommand` observation confirms that storing `cs 1` leaves
cl.serverId unchanged; fetching it applies the new ID. The remote source therefore
advances cvar effects and its outgoing ID on consumption. New gamestates still
supply an immediate baseline. Eleven packet-backed remote-source tests cover this
ordering along with snapshot/gamestate, fragment, history and restart behavior.

`RemoteSystemInfo` preserves these cvar permissions and reset order. It validates
names/content metadata before applying effects, parses the requested game/pure
state for the host, and never changes mounted files or executes remote text.
The Fabric host requires the already-mounted game and now supports the pure
client path. For a pure gamestate it first checks that referenced normal-checksum
packs are installed. A new gamestate applies the loaded-pack allowlist, restarts
the indexed content view with its checksum feed, and rebuilds cgame, UI, world,
material and audio assets. Original module and asset reads establish reference flags;
the host then queues the native-format `cp` verification command. Missing
referenced packs are reported for manual installation. Server-supplied names
are diagnostics, not host paths or download targets.

A consumed systeminfo update may still advance cvars and the outgoing server ID,
as during a native fast restart. Changing pure mode or the loaded-pack allowlist
within the same gamestate currently produces an explicit reconnect error instead
of partially replacing active content. A new gamestate permits the full rebuild.
Leaving remote play restores the default local filesystem view and menu assets.
The installation is not modified, archives are not downloaded, and another game
directory is not mounted automatically. Malformed numeric/game metadata remains
strictly rejected rather than emulating permissive legacy parsing.

The independent [checksum implementation](PK3_CHECKSUMS.md),
[native filesystem differential](NETWORK_PURE_FILESYSTEM.md), and
[pure command/admission observations](NETWORK_PURE_VERIFICATION.md) establish the
content-side contracts. The actual pure Fabric CPU application replay passed
236/90 frames across two maps, native fast restart, remote-menu/background
continuation, and 60 local frames after disconnect. It verified filtering and
restoration of a client-only overriding pack; details and the separate graphics
checkpoint are in [remote play](REMOTE_PLAY.md).

```sh
python3 scripts/BuildSystemInfoOracle.py
python3 scripts/AuditSystemInfo.py
```

The systeminfo fixture reads no original media, opens no network sockets and
changes no host cvars or filesystem. Its callback observations establish effect
ordering and cvar permissions; they do not by themselves prove filesystem or
full client lifecycle behavior. Those are covered by the separately linked
filesystem, wire and application audits.
