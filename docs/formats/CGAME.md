# cgame host

The original `vm/cgame.qvm` does prediction, the view, weapons, effects, events, the HUD and the
scoreboard. The host supplies the engine services it imports and feeds it snapshots, reliable
server commands and user commands. The same host serves local, remote and demo play: everything
reaches it through one snapshot pipeline.

Code (`engine/client`):
[`CgameHost`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/host/CgameHost.java)
(cgame imports and exports),
[`GuestVm`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/host/GuestVm.java)
(lifecycle and imports shared with the ui),
[`ClientWorld`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/host/ClientWorld.java)
(collision, marks, entity string),
[`HostServices`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/host/HostServices.java),
[`SnapshotPipeline`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/pipeline/SnapshotPipeline.java),
[`UserCmdHistory`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/pipeline/UserCmdHistory.java),
[`ClientRenderer`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/render/ClientRenderer.java),
[`ClientAssets`](../../engine/client/src/main/java/dev/bluevista/craftq3/engine/client/render/ClientAssets.java),
[`CgameFrame`](../../engine/render-ir/src/main/java/dev/bluevista/craftq3/engine/render/frame/CgameFrame.java);
struct layouts in `engine/vm-abi`'s
[`layout`](../../engine/vm-abi/src/main/java/dev/bluevista/craftq3/engine/vmabi/layout/) package.
Every import with its number, retail number, policy and neutral value is listed in
[SYSCALLS.md](../SYSCALLS.md).

## Lifecycle

`start()` needs a gamestate in the pipeline. It loads `vm/cgame.qvm` (ABI detected as in
[QVM.md](QVM.md)) and calls `CG_INIT(serverMessageNum, serverCommandSequence, clientNum)`, which
loads the map and every asset. When the pipeline moves to another level the host is stale and is
restarted; a video restart also restarts it on the same level. Each frame calls
`CG_DRAW_ACTIVE_FRAME(serverTime, 0, demoPlayback)`. A guest fault or `trap_Error` drops to the
menu (see [ARCHITECTURE.md](../../ARCHITECTURE.md)).

`trap_UpdateScreen` inside `CG_INIT` re-enters `CG_DRAW_ACTIVE_FRAME` to draw the loading screen
with the ui's connect screen as overlay, as the original does; anywhere else it hands over the frame
drawn so far. See [DIVERGENCES.md](../DIVERGENCES.md).

## Snapshots and commands

- `trap_GetSnapshot(n)`: a number above the latest received is an error (as in the original);
  snapshots 32 or more messages old (`PACKET_BACKUP`) or never received return 0. The snapshot is
  written in the guest's profile layout with at most 256 entities.
- `trap_GetServerCommand(n)`: the last 64 reliable commands are kept. A command already cycled out
  is an error (tolerated during demo playback) and so is one not yet received. Configstring
  commands (`cs`, `bcs0/1/2`) update the cgame's own configstring view when it executes them;
  `disconnect` ends the cgame; `map_restart` clears the user commands; the command is tokenized as
  one line (semicolons included), and `trap_Argv` reports it until the next one.
- `trap_GetUserCmd`: the last 64 user commands (`CMD_BACKUP`); asking for a future one is an error.
- `trap_SetUserCmdValue` stores the selected weapon and sensitivity scale for the next commands.

## Struct layouts

The 1.32 and 1999 retail cgames use different layouts; the canonical form everywhere outside the VM
is 1.32, and conversion happens only in the layout classes:

| Structure | 1.32 | Retail |
| --- | ---: | ---: |
| `entityState_t` | 208 | 204 (no `generic1`) |
| `playerState_t` | 468 | 444 (`ping` at 440; converting copies the first 440 bytes) |
| `snapshot_t` | 53,772 | 52,724 |
| `glconfig_t` | 11,332 | 4,164 |
| `usercmd_t` | 24 | 24 |

`usercmd_t` 1.32: `serverTime` 0, angles 4/8/12, `buttons` (int) 16, `weapon` 20, moves 21/22/23.
Retail: `serverTime` 0, `buttons` (byte) 4, `weapon` 5, angles 8/12/16, moves 20/21/22.
`gameState_t` holds 1,024 string offsets and 16,000 bytes of string data in both. Mark fragments
are 8 bytes ([MARKS.md](MARKS.md)), `polyVert_t` 24 bytes.

## Services

- **Collision**: map and inline models, temporary box models, point and transformed contents, box
  and capsule traces through `ClientWorld` ([COLLISION.md](COLLISION.md)); mark fragments
  ([MARKS.md](MARKS.md)). An unreadable map ends the cgame.
- **Renderer**: model, skin and shader registration; scenes with reference entities, polygons and
  dynamic lights; 2D pictures; model bounds and MD3 tag interpolation. `ClientRenderer` records the
  calls of the cgame, then the ui, into one `CgameFrame` in submission order. Out-of-range values
  are clamped and submissions past the frame budgets dropped with a warning, so renderer imports
  never fail. Budgets: 16,384 commands and 32 views per frame; per view 1,024 entities, 4,096
  polygons and 128 lights.
- **Assets** (`ClientAssets`): 8,192 handles per table; handle 0 is "none" for models and skins and
  the default shader for shaders. A missing file registers as 0 and logs once. Shaders without a
  script get the implicit 2D material; MD3 surface shaders get diffuse lighting
  ([SHADERS.md](SHADERS.md)). Decoded images are limited to 256 MiB.
- **Fonts**: `trap_R_RegisterFont` reads the pre-rendered `fonts/fontImage_<size>.dat` (12 when no
  size is given); FreeType rendering is not supported.
- **Sound**: registration and playback through `engine/audio`, including entity loops and
  background music ([WAV.md](WAV.md)).
- **Cinematics**: `trap_CIN_*` ([ROQ.md](ROQ.md)).
- **Shared**: print, error, milliseconds, cvars, arguments, files through the home store and search
  path, memory and math intrinsics (`engine/vm-abi` `service` package).

Independent implementation; see [LICENSE-NOTES.md](../../LICENSE-NOTES.md). Public references:
id Software's [cg_public.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/cgame/cg_public.h),
[tr_types.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/cgame/tr_types.h) and
[q_shared.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/q_shared.h).
