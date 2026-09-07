# CraftQ3

**Quake III, reimplemented through Minecraft.**

CraftQ3 is an independent Quake III-compatible runtime implemented as a Minecraft Fabric mod, loading original user-supplied Q3 game data and QVMs directly while using Minecraft as the host platform.

The Fabric client now runs a **local Quake gameplay session**: original qagame, cgame and UI bytecode provide movement, weapons, pickups, prediction, camera and HUD, using independent BSP collision, MD3 rendering and PCM sound services. Original bots are enabled for local play, including the Single Player introduction against Crash ([usage and validation](docs/BOT_PLAY.md)). Vulkan captures and scripted screen input verify movement, jumping, mouse look, firing and respawn with bots. Direct protocol-68 remote connections now use the original cgame and menus, including native-verified pure PK3 checksums/references and CPU application coverage for server restart, map change, restricted content and return to local play ([connection usage](docs/REMOTE_PLAY.md)). The original multiplayer browser now supplies discovery, pings, filters, favorites and status through both UI profiles ([browser support](docs/SERVER_BROWSER.md)). Protocol-68 demos now record local and remote matches and play through original cgame, with original Demos-menu access ([demo usage](docs/DEMO_PLAY.md)). Original Create Server menus now host remote players, with CPU coverage for both QVM profiles ([hosting usage and limits](docs/NETWORK_SERVER_SESSION.md)). **This is not the complete Quake game yet:** server hosting and the remaining bot, legacy demo, menu and compatibility work are still in progress; Minecraft interoperability is now an experimental gameplay path. No original engine runs underneath, and no copyrighted game data, asset conversion or Minecraft block proxies are used.

## Pinned development target

| Component | Version |
| --- | --- |
| Minecraft Java Edition | **26.2** |
| Java toolchain / bytecode | **25** |
| Fabric Loader | **0.19.3** |
| Fabric API | **0.156.0+26.2** |
| Fabric Loom | **1.17.20** (release, not SNAPSHOT) |
| Gradle wrapper | **9.6.0** |

Minecraft 26.2 uses unobfuscated names; this build uses `net.fabricmc.fabric-loom` without Yarn mappings. CraftQ3 uses Blaze3D GPU buffers, pipelines and render passes, supporting Minecraft's experimental Vulkan backend and its OpenGL backend through the same implementation. There are no direct OpenGL or Vulkan calls.

## Build and launch

Install a Java 25 JDK and set `JAVA_HOME` to it. On macOS, `export JAVA_HOME=$(/usr/libexec/java_home -v 25)` selects a locally installed JDK.

```sh
./gradlew build
./gradlew :craftq3-fabric:runClient
```

Import the Gradle project in IntelliJ IDEA with Gradle JVM 25. Loom generates the **CraftQ3 Client** run configuration (`./gradlew :craftq3-fabric:idea` can regenerate IDE runs). The development game directory is `run/`. The distributable is `craftq3-fabric/build/libs/craftq3-0.1.0.jar`, containing the engine modules as nested jars. Install it alongside the pinned Fabric API in a Fabric 26.2 client. The Fabric entry point is client-only; the engine's local qagame host is not a network or dedicated Fabric server yet.

For Vulkan, select **Options → Video Settings → Graphics API → Prefer Vulkan (Experimental)** and restart Minecraft. The engine respects Minecraft's chosen backend and does not change users' global graphics settings. The debug mesh upload log reports the backend actually used. Unsupported hardware may cause Minecraft to fall back; inspect the log rather than assuming Vulkan was selected successfully.

## User-owned game data

First launch creates:

```text
<game directory>/config/craftq3/craftq3.properties
<game directory>/craftq3/games/baseq3/
```

For this development checkout, drop `pak0.pk3` into `run/craftq3/games/baseq3/` without extracting it. **Pak0 alone is sufficient for the current local gameplay session**, including its original qagame/cgame/UI modules, maps, models and sounds. Run `/q3 reload` after adding packs when using the existing diagnostic mount; a new gameplay session mounts the configured installation afresh.

The `installation` property is a directory **containing** `baseq3`, not `baseq3` itself. Set it to an existing Quake III installation to avoid copying or extracting anything. `game=baseq3` is the default; another immediate subdirectory selects a mod and retains baseq3 fallback. Relative installation paths in the configuration resolve against the configuration directory. The properties file is UTF-8; the `/q3 path` command writes it safely, including spaces and backslashes.

Choose **CraftQ3** on Minecraft's title screen to open the original Quake III menu without loading a Minecraft world. To enter an arena directly, press backtick and type `map q3dm17`. The original retail CD-key dialog can be dismissed with Escape through its normal UI path; key storage/verification is not connected yet. The original Single Player menu starts the introductory match against Crash; default-rule victory, the Tier 1 unlock movie, original next-arena selection and saved progression now pass through the production session. To add an opponent to a directly loaded arena, enter `addbot sarge 3` in the Quake console. The original Multiplayer menu can discover and join servers. The Demos menu plays supported recordings. The original Cinematics menu now plays RoQ movies with synchronized audio, skipping and return-menu behavior ([movie controls](docs/CINEMATICS.md)). Original protocol-43 `.dm3` files and full campaign coverage remain unfinished.

For the experimental gameplay bridge, open a **singleplayer Minecraft world** and run `/q3 bridge`. Quake movement, jumping, weapon controls and HUD run over Minecraft terrain; Escape returns to Minecraft. Quake shots now damage Minecraft mobs, with blocks providing cover. Mobs can attack back, with original Quake armor, death and respawn. Press backtick for the Quake console; `give all` grants the original loadout, and Escape closes the console before returning to play. From a local Quake match with a Minecraft world open underneath, enter `minecraft` in the Quake console to carry your live loadout into the bridge. Use `quake q3dm17` in the bridge console to carry the updated loadout into a new Quake match. Your Quake loadout saves on normal exit and returns on the next bridge entry; `/q3 bridge fresh` starts with the original default loadout. Place original pickups at your Minecraft position with `/q3 pickup add weapon_rocketlauncher` (or `ammo_rockets`, `item_armor_combat`, etc.), then re-enter the bridge to collect them; placements persist with the world. For the reverse direction, run `/q3 build q3dm17` to build with Minecraft blocks in the original BSP; native water/lava buckets and fluid spread now respect BSP surfaces; `/q3 leave` returns to your world. See [bridge controls and limits](docs/MINECRAFT_BRIDGE.md) and [Minecraft building](docs/MINECRAFT_BUILDING.md).

For multiplayer, use the original **Multiplayer** menu or enter `connect <host[:port]>` in the Quake console (default port 27960). The connection flow supports protocol-68 servers, including pure servers, with compatible installed modules/assets. Enable `cl_allowDownload 1` to download missing non-official server PK3s into an isolated cache, verify them, and resume connecting automatically. The server must allow UDP downloads. Official packs require local installation; automatic game-directory switching remains unsupported. The original connection/in-game menus, `disconnect`, and return to local play are connected; remote matches keep running while menus are open. See [remote support and limits](docs/REMOTE_PLAY.md).

To record a match, enter `record mymatch`, then `stoprecord`. Play it with `demo mymatch` or the original **Demos** menu. Recordings live in `<game directory>/craftq3/demos/baseq3/` (development: `run/craftq3/demos/baseq3/`). See [demo controls and compatibility](docs/DEMO_PLAY.md).

Alternatively, enter a Minecraft world to access the client commands:

```text
/q3
/q3 path /absolute/path/to/Quake III Arena
/q3 fs status
/q3 fs list maps
/q3 fs which maps/q3dm17.bsp
/q3 game alternatefire
/q3 game baseq3
/q3 reload
/q3 menu
/q3 map q3dm17
/q3 view q3dm17
/q3 debug bsp
```

`/q3 path` takes the entire remaining line, so do not put quotes around a path containing spaces. It switches to baseq3. Changes are persisted only after a successful mount. Failed mod switches preserve the previous mount and configuration. `reload` rebuilds the index, rereads configuration, and clears the loaded map. Asset work runs on a worker thread; concurrent commands receive a busy response.

`/q3 map` (or `/q3 play`) starts local gameplay. W/A/S/D move, mouse looks, left mouse fires, Space/right mouse jumps, C/Ctrl crouches, Shift walks, number keys/wheel select owned weapons, Enter uses a holdable, and Tab shows scores. Backtick opens the console with the original Quake font and animated material; **Escape opens the original in-game menu**, which pauses the local game, and **Shift+Escape returns to Minecraft**. `disconnect` returns to the original main menu; `quit` exits Quake mode. `map`, `devmap`, `spmap` and `map_restart` dispatch at a safe command boundary. Settings and bindings persist across map changes; archived settings are saved in the game home. The console supports cursor editing, Up/Down history, Tab command/cvar completion, and Page Up/Down or mouse-wheel scrollback. The original VMs implement movement, weapons, menu presentation and settings behavior.

`/q3 view` opens the independent **noclip inspection viewer**, using a spawn entity when available. W/A/S/D move, Space/C move vertically, arrow keys look, Shift speeds up, and Escape returns. The following diagnostic keys apply to this viewer:

| Key | Action |
| --- | --- |
| **F8** | Toggle diagnostics: rolling FPS, average/worst frame time, geometry counts, draw calls, camera, backend, PVS and asset warnings |
| F6 | Cycle materials, wireframe, surfaces, patches, normals, nodes, leaves, PVS colors, lightmap indices, lightmaps, and collision brushes |
| F7 | Toggle PVS filtering; missing visibility or an outside-world camera uses conservative visibility |
| F9 | Pause/resume shader animation |
| F10 | Toggle a camera light to inspect dynamic lighting |

Textures, lightmaps, multiple shader stages, animated maps, blend/alpha tests, color/UV generators, texture transforms, curved surfaces, vertex deformation, billboards, fog, skies and one-level portals/mirrors are rendered through Blaze3D. [Compatibility notes](COMPATIBILITY.md) describe the remaining fidelity and entity-system limits.

During either Quake view, Minecraft world extraction/rendering and its HUD are suppressed. Local Quake simulation has its own clock; Minecraft's 20 Hz tick does not control input or prediction. The session restores the host cursor and audio listener on exit. Saved game files, when supported securely by the host filesystem, live under `<game directory>/craftq3/home/<game>/`; original PK3s remain read-only.

## Tests and diagnostics

```sh
./gradlew check
./gradlew spotlessApply
./gradlew :craftq3-assets:generateDebugFixture
./gradlew :craftq3-fabric:runDebugClient
```

`generateDebugFixture` explicitly generates an original synthetic curved-patch BSP under `run/craftq3/games/baseq3/maps/craftq3_fixture.bsp`. Run `/q3 reload`, then `/q3 view craftq3_fixture` to inspect it without Quake assets. It is never packaged or committed. Tests cover filesystem precedence/security, config persistence, all BSP lumps, malformed input, coordinate mapping, and patch geometry. No test requires commercial game files. Logs are in `run/logs/latest.log`, under the `CraftQ3` logger.

See [ARCHITECTURE.md](ARCHITECTURE.md), [ROADMAP.md](ROADMAP.md), [COMPATIBILITY.md](COMPATIBILITY.md), [LICENSE-NOTES.md](LICENSE-NOTES.md), and [format notes](docs/formats/README.md) for boundaries, limitations, provenance and next tasks.

For repeatable graphics tests:

```sh
./gradlew :craftq3-fabric:runSmokeClient -Pq3Map=craftq3_shaderlab
./gradlew :craftq3-fabric:runDebugClient -Pq3Map=q3dm12
./gradlew :craftq3-fabric:runPlayClient -Pq3Map=q3dm17
./gradlew :craftq3-fabric:runPlaySmokeClient -Pq3Map=q3dm17 -Pq3InputTest=true
./gradlew :craftq3-fabric:runPlaySmokeClient -Pq3Map=q3dm17 -Pq3BotTest=true
```

The first command generates an original shader test map, opens it, fixes shader time at 1.0 seconds, captures frame 180, and exits. No commercial assets are needed for the fixture. Run once with each graphics backend selected in the development client, then compare:

```sh
java scripts/VerifyShaderCapture.java run/screenshots/craftq3-craftq3_shaderlab-materials-vulkan.png
java scripts/CompareCaptures.java run/screenshots/craftq3-craftq3_shaderlab-materials-opengl.png run/screenshots/craftq3-craftq3_shaderlab-materials-vulkan.png
```

Use `-Pq3Panel=true` to include diagnostics (FPS text varies between runs), or `-Pq3Mode=WIREFRAME` to capture another debug view. `scripts/AuditAssets.java` optionally audits a user installation; its usage is in the source. Captures and generated game data remain ignored. Automatic launch/capture switches are development-only. See [the validation record](docs/VALIDATION.md) for actual results and limits.

The optional local gameplay audit executes your original `qagame.qvm` independently of Minecraft:

```sh
./gradlew :craftq3-server:auditGameplay -Pq3Map=q3dm17
```

It exercises startup, spawn, movement, firing, death and respawn, then checks a fixed-seed 140-frame replay. It requires user-owned PK3s and is separate from asset-free CI tests. The playable smoke test also exercises the screen's key/mouse callbacks, original HUD and audio on the selected GPU backend. The bot capture runs 900 presentation frames with three original bots and sends normal fire-button input to respawn a dead player before capture. `-Pq3Installation=/path/to/Quake` selects a development-only installation without rewriting saved configuration. These checks do not establish completed networking, every bot navigation case or all menu actions. `runLifecycleSmokeClient` additionally verifies original main-menu/map/disconnect transitions, persistent settings and bindings, and retained-client map restarts on the selected GPU backend. The user now prioritizes the **Minecraft gameplay bridge**, with remaining standalone compatibility work tracked alongside it.

Client settings and bindings are saved to `craftq3/home/<game>/q3config.cfg` under the Minecraft instance directory. `autoexec.cfg` in that home directory loads afterward; nested `exec` reads the home copy before the mounted game data. Use `writeconfig` in the Quake console to save immediately. Config writes use the isolated game home and never change your PK3s.

The numbered remaining-work list is in [What’s left](docs/WHATS_LEFT.md).
