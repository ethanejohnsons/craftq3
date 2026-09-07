# Original UI QVM host

`Q3Ui` in `craftq3-client` executes the user's original `vm/ui.qvm`. Menus, labels,
layout, cursor movement, focus, player preview selection and menu commands come
from that module. It reuses the cgame host's CPU asset registry and immutable
`CgameFrame` submissions. The renderer can display these frames without a BSP
world or composite them over an active game. No Quake UI source, game modules,
menu images, models, sounds or license keys are bundled.

## Borrowed engine services

The constructor takes the filesystem, shared `CvarSystem`, engine `CommandSystem`,
`KeyBindings`, audio backend, frame sink, diagnostic sink and `UiHost`. An optional
`UiAbi` selects a separately verified legacy profile. Cvars reserve the valid
zero handle for `sv_cheats`. Menu edits use the same cvars and bindings as local
gameplay; command execution is submitted to the borrowed engine command system.

`UiHost` supplies connection state, configstrings, key-catcher state, physical
key queries and key clearing. Clipboard and CD-key storage/verification are
explicit callbacks. `UiHost.disconnected()` provides isolated menu state before
a server exists. It supplies no key and does not pretend to validate one. An
empty key is invalid; nonempty-key storage or verification requires a configured
host implementation. The retail UI also uses its original cvar-based key field.
Without a configured key, the original UI can show its CD-key dialog. Ordinary
Escape/disconnected-main-menu transitions remain decisions of the original VM
and surrounding engine; the host does not forge a key or force checked flags.

`initialize(width,height)` checks the guest UI API and invokes `UI_INIT`.
`setMenu(Menu)`, `key(key,down,time)`, `mouse(dx,dy,time)`, `fullscreen()` and
`consoleCommand(Command)` call the corresponding original exports. `frame(time,
width,height)` performs `UI_REFRESH` and returns owned renderer commands;
`drawConnectScreen(overlay)` supports the guest connection display. A viewport
change resets/reinitializes guest presentation and reopens the active top-level
menu while retaining shared cvars, bindings and registered assets.

The caller advances engine command buffers and owns the enclosing audio
`beginFrame`/`endFrame` lifecycle. The UI does not clear game-loop audio when used
as an overlay. `close()` shuts down the guest, stops its background track and
closes guest file handles, leaving borrowed engine services open.

## Verified profiles and ABI

The retail UI SHA-256 is
`826a342a108ac8a7fa45f4e752dfa5be50fa5ddf4fdb87d7c404d833c4989627`.
Only this exact module automatically selects `RETAIL_1999`; others default to the
modern layout. Its reported UI API is 3. The unchanged modern base-game reference
module reports API 4. API 6 is accepted at the ABI level, but Team Arena-specific
services and complete menus are not claimed supported.

Retail UI imports 46–49 are separate local/global server-list services; its
ping/cvar/memory services 50–56 map to modern 46–52. The first original retail
registration call was verified as trap 54 with a VM cvar pointer, cvar name,
default string and flags. Unverified retail imports 57–99 fail explicitly.
Retail `UI_KEY_EVENT` forwards only the key argument: the host filters releases.
Modern UI receives both the key and its down state. These differences were
verified from original guest argument accesses and executable tests.

Renderer structures use the same explicit profiles as [cgame](CGAME.md): retail
`glconfig_t` is 4,164 bytes; modern is 11,332. The UI client-state structure is
3,084 bytes, with connection state, packet count and client number followed by
three 1,024-byte strings. Modern trap numbers and exported menu calls come from
the public [UI declarations][ui]. Implementation is independently authored Java;
engine implementation functions were not copied or mechanically translated.

## Services and limits

Implemented services include print/error, logical milliseconds, cvar values and
VM cvars, argument access, engine command submission, read-only filesystem
handles, direct-child file/directory listing, model/skin/shader/sound registration,
scene and HUD drawing, lights, MD3 tag interpolation and model bounds, local
sounds/background tracks, binding/key/overstrike state, clipboard callbacks,
glconfig, client state, configstrings and basic memory/math intrinsics.

`FS_GETFILELIST` derives direct child directories from mounted virtual files for
the `/` extension; file suffix matching is case-insensitive through canonical
paths. Results are sorted, deduplicated and NUL-separated within the guest's
capacity. `$modlist` conservatively reports mounted game directories. It does not
walk arbitrary host directories. Missing files return the guest's missing-file
result; invalid handles, paths and buffer ranges remain errors.

The original server browser is backed by session-owned lists, discovery, pings,
status, sorting, filters and persistent favorites in both UI profiles. Valid
list/ping results zero-pad the guest buffer; missing slots write a leading NUL,
and pending status requests preserve existing bytes. See [browser support](../SERVER_BROWSER.md).
Cinematic playback, fonts, preprocessor sources and later renderer extensions
are not yet fully provided. Unimplemented services
identify their trap number and fail explicitly. UI file writes require a saved-file
capability and are currently rejected; surrounding engine config persistence is
separate. Background music has the same whole-PCM/frame-clock limits as cgame.

QVM work, syscall, stack and deadline bounds match the cgame host. Asset and frame
budgets are shared implementations. UI file-list buffers are limited to 1 MiB and
65,536 directory entries. Key numbers, mouse deltas, viewport dimensions,
configstring indices and guest ranges are checked. Diagnostics expose the
selected profile, API, registered asset counts, interpreter statistics and
original syscall counts.

## Validation

`./gradlew :craftq3-client:test` includes original synthetic UI guests for both
profiles. Tests exercise registration-number differences, release filtering,
shared cvars/bindings, directory deduplication, borrowed connection/configstring
state, viewport reset, malformed guest memory and borrowed-service ownership.

`./gradlew :craftq3-client:auditUi` uses the user's local installation. It opens
the native initial prompt and main menu, navigates Setup and Player Settings,
returns with Escape, moves the mouse and resizes the viewport. It requires
nonempty menu frames, an original banner model, player-model registrations and
navigation sounds, then repeats to compare a deterministic digest. Use
`-Pq3Installation=/path/to/games` and optionally
`-Pq3AuditUiVm=/path/to/ui.qvm`. No external modules or assets enter the build.

[ui]: https://github.com/id-Software/Quake-III-Arena/blob/master/code/ui/ui_public.h
