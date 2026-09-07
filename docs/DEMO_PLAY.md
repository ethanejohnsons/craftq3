# Recording and playing demos

Open CraftQ3 and press backtick to use the Quake console. Start a local game or join a compatible
protocol-68 server, then enter:

```text
record mymatch
stoprecord
demo mymatch
```

`record [name]` starts a recording of the current local or remote game. Omitting the name chooses
the first unused `demo0000.dm_68`, `demo0001.dm_68`, and so on in CraftQ3's recording directory.
Named recordings append `.dm_68` when needed. Names must be flat filenames, such as `mymatch`,
without directories. `stoprecord` finishes and saves the file; the console reports its saved name.
A successfully finished named recording replaces an existing recording of the same name. Starting
another recording while one is active reports the existing recording instead. Playback cannot
itself be recorded.

`demo name` accepts a basename or `.dm_68` filename, optionally prefixed with `demos/`. It leaves the
current game, loads the recorded map and runs original cgame against the recorded snapshots and
commands. It does not join the recorded server. Reaching the end returns to the original main menu;
`disconnect` also stops playback. Escape, ordinary typing, Space and left mouse exit while the
game owns input; arrows and function keys do not. Backtick opens the host console for playback
controls. `com_cameraMode 1` suppresses the ordinary letter/mouse exit behavior; Escape still exits.

For a sequence of demos, enter `set nextdemo "demo second"` before playing the first. At normal completion
the host clears that value and queues its command for the next frame. Ordinary demo-exit keys clear
the pending chain. This is a console setting you supply, not a command accepted from recorded data.

## Files and the original menu

CraftQ3 stores recordings separately from the installed Quake data:

```text
<Minecraft instance>/craftq3/demos/<game>/mymatch.dm_68
```

For the default game, `<game>` is `baseq3`. This path remains inside the Minecraft instance even
when the configured Quake installation is elsewhere. To import a compatible recording, place its
lowercase, flat `.dm_68` file in this directory before opening CraftQ3. The host checks this directory
first, then the mounted installation's `demos/` files, including files in PK3 archives. Existing
installed files are read without rewriting or extracting the archives. Playback still requires
the demo's compatible map, modules and other game assets to be installed in the selected game.

The original Demos menu includes supported installed and host-owned recordings. The retail UI asks
for `.dm3` names, so CraftQ3 supplies reversible display aliases for actual `.dm_68` files. Selecting
`mymatch.dm3` in that menu can therefore open `mymatch.dm_68`. This is a menu compatibility layer,
not conversion: **physical protocol-43 `.dm3` demos are not supported**. An existing `.dm3` file or
an ambiguous case spelling blocks a conflicting alias rather than silently opening another file.

The recording directory allows **512 MiB per file, 2 GiB total stored data, and 128 files**.
Recordings stream to a temporary file and are published atomically when finished. A failed startup, I/O failure, aborted write or failed commit preserves the previous file.
If a healthy recording reaches its byte quota, the host can save its completed prefix with a clean
end marker, replacing an existing file of the same name; an interrupted process may leave a temporary file that
counts toward the quota and requires manual recovery. The separate directory does not expand the
VM filesystem's 16 MiB buffered-file limit. Installed/PK3 demo reads still use the existing bounded
asset reader. See [storage guarantees](DEMO_STORAGE.md) for the exact limits and failure policy.

## Timing controls

Enter these commands in the Quake console:

| Command | Effect |
| --- | --- |
| `cl_freezeDemo 1` | Hold ordinary demo presentation time after activation. |
| `cl_freezeDemo 0` | Resume ordinary presentation. |
| `timescale 0.5` | Play at half speed. |
| `timescale 2` | Play at double speed. |
| `timescale 1` | Restore normal speed. |
| `timedemo 1` | Advance presentation by 50 ms per active rendered frame. |
| `timedemo 0` | Restore ordinary clock behavior. |

Timescale defaults to 1 and accepts finite values from 0 through 1000 for demo playback. Demo start
enables cheat-protected playback controls and retains the existing timescale. Freeze does not stop
the initial priming reads, and timedemo overrides frozen presentation. Timedemo runs according to
the host's rendered frames. Completion prints counted frames, elapsed seconds and FPS when a
timedemo sample is available; this host report does not claim exact native reporting cadence or
native rendering speed. Reset timing controls before the next demo if you want ordinary playback.

## Compatibility and evidence

Current CPU application checks cover both retail and matching-modern UI/cgame profiles: local
recording, original-menu selection, frozen presentation and end-of-demo return. A modern remote
recording also plays through native fast restart and a map change. Missing files/maps, demo exit keys,
timedemo/chaining, and a failed replacement recording are covered by the same application audit. These checks exercise the actual host and original
VMs; they do not substitute for a new Vulkan demo capture or complete demo-format coverage.

Protocol-68 framing, initial recording gamestate, command handling and start/end effects have
separate [native observations](NETWORK68_DEMO_HOST.md). The [demo clock](DEMO_SERVER_CLOCK.md) is
also checked against unchanged native calls. Recorded system information remains playback data:
it cannot switch the mounted game, apply a remote pure-pack allowlist or execute arbitrary host
commands. Playback clears an existing server restriction and rebuilds assets with the recorded
checksum feed in the currently selected game.

Protocol 43, seeking, video export and exact native timedemo summary compatibility remain unimplemented.
Unsupported or malformed data is reported rather than treated as another protocol. Boundary EOF,
short final headers and short final payloads end playback with a recorded end reason; unsafe packet
lengths and other parser errors stop the failed playback explicitly.
