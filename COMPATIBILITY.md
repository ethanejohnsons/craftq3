# Compatibility

CraftQ3 aims to run user-supplied Quake III Arena data exactly as the original 1.32 engine does:
the same search order, the same QVMs, the same network protocol, the same behaviour. Deliberate
differences are listed in [docs/DIVERGENCES.md](docs/DIVERGENCES.md).

## Reference installation

Compatibility is measured against the maintainer's installation, which is never committed: retail
`pak0`–`pak8`, 185 community PK3s, and nine mods (CPMA, Jailbreak, True Combat, Combat, Q3Rally,
Alternate Fire, Superheroes Arena, Q3Pinball, CorkScrew). It holds 278 PK3s, 29,505 archive
entries, 314 BSPs and about 67 distinct QVM builds.

Run the sweep against your own installation with:

```bash
CRAFTQ3_Q3ROOT="/path/to/Quake III Arena" ./gradlew :engine:headless:compatSweep
```

It writes `engine/headless/build/compat/report.md` and `report.json`. For every game directory it
mounts every PK3, loads every map (BSP, AAS, collision model, render scene and materials), boots
the effective ui, cgame and qagame, plays a local match with four bots under
`vm_strictSyscalls 1`, plays every demo and RoQ, and counts any import that had to degrade.

## Latest sweep (30 s matches, 4 bots)

| Game | PK3s | Maps | ui | Match | Demos | RoQ | Degradations |
| --- | ---: | --- | --- | --- | --- | --- | ---: |
| baseq3 (1.32) | 194 | 209/209 | 1.32 | pass | 7/7 (+2 `.dm3`, see below) | 12/12 | 0 |
| baseq3, pak0 only (1999 retail) | 1 | 31/31 | retail | pass | 2/2 | 11/11 | 0 |
| CorkScrew | 195 (15 empty, skipped) | – | 1.32 | pass | – | – | 0 |
| alternatefire | 195 | – | 1.32 | pass | – | – | 0 |
| combat | 205 | 11/11 | 1.32 | pass | – | – | 0 |
| cpma | 237 | – | 1.32 | pass | 1/1 | – | 0 |
| jailbreak | 197 | 17/17 | 1.32 | pass | – | – | 0 |
| q3pinball | 195 | – | 1.32 | pass | – | – | 0 |
| q3rally | 196 | 8/8 | 1.32 | pass | – | – | 0 |
| sha | 195 | 1/1 | 1.32 | pass | – | 1/1 | 0 |
| truecombat | 200 | 23/23 | 1.32 | pass | – | 1/1 | 0 |

Mod rows mount the mod over baseq3, so their PK3 counts include baseq3's. Every QVM build in the
installation, including the older builds shadowed by `pak8.pk3`, also parses and validates
(`engine:vm` real-data test).

Notes on the data itself:

- **Missing textures** (199 in baseq3, 36 in pak0 alone) are references to files the installation
  does not contain, mostly Team Arena assets used by community maps. The original engine shows its
  default texture for these, and so does CraftQ3.
- **Legacy demos**: the three `.dm_48` demos (Quake III 1.25–1.27, in `pak4`/`pak5`) and pak0's
  two `.dm3` demos (1.09, protocol 43) all play; the original 1.32 engine plays neither format.
  The `.dm3` demos need the retail cgame (pak0-only profile): the 1.32 cgame rejects their
  gamestate, exactly as it would on the original engine.
- **Empty PK3s**: 15 zero-byte `CorkScrew_*.pk3` files are skipped with a warning, as the original
  engine skips archives it cannot open.
- **`almostlost.bsp`** is an IBSP version 47 map (RTCW layout); CraftQ3 loads it, the original 1.32
  engine refuses it.
- **Loose `baseq3/qagame.qvm`** is outside `vm/`, so it is never loaded as the game module.
- **`cpma/cpma baseq3/`** holds map PK3s one level too deep to be a game directory; copy them into
  `baseq3/` to use them, as with the original engine.
- **Mod quirks reproduced faithfully**: Jailbreak's bot AI crashes when a team has a single bot,
  and Combat's deathmatch maps have no items (its bots idle there).

## Feature coverage

| Area | Status |
| --- | --- |
| Filesystem | PK3 and loose files with original precedence, pure servers, UDP downloads of referenced non-official PK3s, portable home store (Windows, Linux, macOS) |
| QVMs | All qagame, cgame, ui and botlib imports bound for the 1.32 and 1999 retail ABIs; see [docs/SYSCALLS.md](docs/SYSCALLS.md) |
| Single player | Local matches with bots, original menus, map changes, restarts, pause |
| Multiplayer | Protocol 68 client and server, server browser (LAN, masters, favourites), rcon, dedicated headless server |
| Bots | Full botlib: routing, all travel types (ladders, elevators, jump pads, bobbing platforms, rocket/BFG jumps, grapple), chat, goals, weapons, alternative route goals for CTF |
| Demos | Record and play protocol 66/67/68; play 1.09 (`.dm3`) and 1.25–1.27 (`.dm_48`) demos; timedemo |
| Cinematics | RoQ with audio, menu cinematics, `videoMap` surfaces |
| Rendering | All shader stage features on the GPU, lightmaps, sky boxes and clouds, fog, dynamic lights, portals and mirrors, MD3 models, beams/rails/lightning, HUD, fonts from `fonts/fontImage_N.dat`; Vulkan and OpenGL through Minecraft's Blaze3D |
| Audio | Original channel and loop semantics through Minecraft's OpenAL device, background music, cinematic audio |
| Minecraft | Pure Quake III from the title screen; bridge mode (Quake over Minecraft terrain with mobs); building mode (Minecraft blocks inside original maps); dedicated Minecraft servers |

Not supported: Team Arena (`missionpack`), native game modules (`.so`, `.dll`), stencil shadows
(`cg_shadows 2`) and FreeType font rendering.
