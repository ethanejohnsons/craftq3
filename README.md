# CraftQ3

**Quake III Arena, reimplemented through Minecraft.**

CraftQ3 is an independent Quake III Arena–compatible runtime packaged as a Minecraft Fabric mod.
It loads your own Quake III installation and runs its original game, client-game and menu
bytecode (QVMs) on an engine written from scratch in Java: filesystem, VM, collision, bot
library, networking, sound and a Blaze3D renderer that works on Minecraft's Vulkan and OpenGL
backends. No original engine code runs and no game data ships with the mod.

- **Pure Quake III** from Minecraft's title screen: the original menus, single player with bots,
  multiplayer over protocol 68 (server browser, hosting, pure servers, downloads), demos and
  cinematics.
- **Bridge mode**: Quake movement, weapons and HUD over a Minecraft world, fighting Minecraft mobs.
- **Building mode**: place Minecraft blocks inside an original Quake III map.
- Works with mods that ship QVMs (CPMA, Jailbreak, True Combat, Q3Rally and others); see
  [COMPATIBILITY.md](COMPATIBILITY.md).

## Requirements

| Component | Version |
| --- | --- |
| Minecraft Java Edition | 26.2 |
| Fabric Loader | 0.19.3 or later |
| Fabric API | 0.156.0+26.2 or later |
| Java | 25 |
| Quake III Arena | your own installation (at least `baseq3/pak0.pk3`; the 1.32 point release adds `pak1`–`pak8`) |

## Install

1. Build the mod (see below) or take `mc/fabric/build/libs/craftq3-0.1.0.jar`, and put it in your
   Fabric `mods/` folder with Fabric API.
2. Start Minecraft. Point CraftQ3 at the directory that **contains** `baseq3`:

   ```text
   /q3 path /path/to/Quake III Arena
   ```

   This writes `config/craftq3/craftq3.properties` (`installation=...`, optional `game=` for a mod
   directory). `/q3 status` shows what was found. PK3s are read in place; nothing is copied or
   extracted.

## Play

- **Title screen → CraftQ3** opens the original Quake III menu without loading a Minecraft world.
- From chat: `/q3 menu`, `/q3 map <name>`, `/q3 connect <host[:port]>`, `/q3 demo <name>`.
- In the Quake console (`` ` ``) every original command works: `map q3dm17`, `addbot sarge 3`,
  `connect`, `record`, `demo`, `quit`.
- **Escape** opens Quake's own menu. **Shift+Escape** always returns to Minecraft.
- Your Quake settings, configs, demos and downloads live under
  `<game directory>/craftq3/home/<game>/`.

Bridge and building modes are described in [docs/MINECRAFT.md](docs/MINECRAFT.md).

The engine also runs without Minecraft:

```bash
./gradlew :engine:headless:run --args='+set dedicated 1 +map q3dm17'
```

starts a dedicated Quake III server on UDP 27960 (with rcon), using `CRAFTQ3_Q3ROOT` or
`+set fs_basepath` to find the installation.

## Build

Install a Java 25 JDK, then:

```bash
./gradlew build
```

```bash
./gradlew :mc:fabric:runClient
```

The development game directory is `run/`. CI runs `./gradlew portableCheck` (every engine module)
on Windows, Linux and macOS, plus the full build on Linux.

Tests that need real game data run only when `CRAFTQ3_Q3ROOT` names an installation:

```bash
CRAFTQ3_Q3ROOT="/path/to/Quake III Arena" ./gradlew check
```

```bash
CRAFTQ3_Q3ROOT="/path/to/Quake III Arena" ./gradlew :engine:headless:compatSweep
```

```bash
CRAFTQ3_Q3ROOT="/path/to/Quake III Arena" ./gradlew :mc:devharness:runClientGameTest
```

The last one renders real maps and plays through the mod on your GPU (`runClientGameTestVulkan`
for Vulkan).

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md): modules, threads, error model, rendering.
- [COMPATIBILITY.md](COMPATIBILITY.md): what runs, measured against a real installation.
- [docs/DIVERGENCES.md](docs/DIVERGENCES.md): deliberate differences from the original engine.
- [docs/SYSCALLS.md](docs/SYSCALLS.md): every QVM import and how it is handled (generated).
- [docs/MIXINS.md](docs/MIXINS.md): every Minecraft hook and why.
- [docs/REWRITE.md](docs/REWRITE.md): the 2026 rewrite plan and its outcome.
- [docs/formats/](docs/formats/): file and protocol format notes.
- [LICENSE-NOTES.md](LICENSE-NOTES.md): provenance and asset rules.

## Legal

CraftQ3 is GPL-3.0 licensed. It contains no Quake III or Minecraft assets; you need your own
copies of both. Quake III Arena is a trademark of id Software; Minecraft is a trademark of
Mojang/Microsoft. CraftQ3 is not affiliated with either.
