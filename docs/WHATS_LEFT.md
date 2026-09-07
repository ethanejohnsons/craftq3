# What's left in CraftQ3

Updated 2026-09-07. This is the remaining scope from the original roadmap, grouped
for prioritization. Numbers match the discussion so items can be kept, deferred or
dropped explicitly. An unchecked item may be missing, partially implemented or
working with insufficient coverage; it does not mean the entire feature is absent.
No scope has been dropped yet. Minecraft 26.2, Java 25 and Vulkan remain the target.

## Core Quake gameplay

- [ ] **1. Full campaign:** all tiers, difficulties, unlocks, awards, ending movies and saved progression. Tutorial victory, the first unlock movie and next-arena reload are verified.
- [ ] **2. All game modes:** complete tournament/team/CTF coverage. Two-versus-two team deathmatch on q3dm1 and CTF on q3ctf1 now pass earned victory, intermission and restart in retail and modern VMs. Other maps, team orders and flag drop/return cases remain.
- [ ] **3. Bot navigation:** elevator integration, failed jump approaches, difficult landings, slopes, low ceilings, remaining routing services and broad stuck-bot checks.
- [ ] **4. Menu/console completeness:** remaining original actions and command paths. Registered console-command dispatch and team-model userinfo are corrected and covered by original team-match checks.
- [ ] **5. Collision/movement edges:** capsule traces, curved-surface edges and wider native comparisons.
- [ ] **6. Long-session reliability/performance:** large bot matches, dense maps, extended sessions and memory/resource behavior.

## Quake mechanics in Minecraft

- [ ] **7. Player combat and multiplayer:** other Minecraft players and server synchronization; current support is local singleplayer with mob targets.
- [ ] **8. Complete damage integration:** potions/status effects, environmental and player-owned attacks/explosions, unusual projectiles, remaining pushes, damage above 255 Quake points, resistance rules and precise attacker/death attribution.
- [ ] **9. Broader mob encounters:** natural targeting and arbitrary terrain beyond the specific tested encounters.
- [ ] **10. Target scaling:** more than the nearest 31 living mobs, currently within 64 blocks.
- [ ] **11. Traveling pickups:** stream distant placed items during travel and preserve item availability/respawn state across bridge sessions.
- [ ] **12. Quake bots in Minecraft:** terrain navigation generation/adaptation and usable bot spawning.
- [ ] **13. Physical interactions:** complex fluids, unusual player poses and more native mechanics.
- [ ] **14. Combined rendering:** translucent ordering, unusual cameras/sub-viewports and block-specific hit effects.
- [ ] **15. Resume previous Quake matches:** current transfers carry loadouts into a new match rather than retaining the previous simulation.

## Minecraft building in Quake maps

- [ ] **16. Concurrent Quake gameplay:** original matches, bots, weapons, pickups and doors/platforms alongside building.
- [ ] **17. Complete shared collision:** Quake players/projectiles versus placed blocks and Minecraft entities while both simulations run.
- [ ] **18. Broader building mechanics:** stairs/slopes, fractional surfaces, curved-surface support, block-specific interactions, fluids, blast/fire effects and projectile persistence.
- [ ] **19. More mob navigation:** large mobs, off-center/tight passages, slopes, swimming and flying.
- [ ] **20. Integrated lighting:** colored/directional Quake lighting on native objects, light exchange and placed-block shadows on BSP.
- [ ] **21. Multiplayer building:** map identity, block/entity synchronization and shared simulation.
- [ ] **22. Region management:** unused geometry eviction and rendering multiple saved regions; current geometry remains until server shutdown.
- [ ] **23. Player body/physics choices:** selectable Minecraft/Quake representations and physics, including Quake-physics Steve.

## Cross-world travel (original stretch features)

- [ ] **24. Seamless portals:** destination rendering, walking through without commands, HUD/physics changes and coordinate continuity.
- [ ] **25. Crossing entities/projectiles:** identity, motion and interactions across concurrently running worlds.

## Quake networking and hosting

- [ ] **26. Broader server compatibility:** public/master browser, modded servers, IPv6 LAN discovery and full IPv6/dual-stack application coverage.
- [ ] **27. HTTP downloads and automatic mod switching:** UDP pack transfer is implemented; HTTP and server-driven game-directory changes remain.
- [ ] **28. Complete hosting:** full native-client application verification, administration, master registration, randomized pure checksum feeds and remaining pure lifecycle policy.
- [ ] **29. Standalone Fabric server deployment:** current hosting runs within the Minecraft client process.
- [ ] **30. Mixed Minecraft/Quake clients:** shared compatible server, an original aspirational feature.

## Expansions and mods

- [ ] **31. Team Arena:** additional gameplay, assets and engine services.
- [ ] **32. QVM mods:** actual tests/fixes for Alternate Fire 2.0, Super Hero Arena and other mods.
- [ ] **33. Mod diagnostics:** module detection, unsupported services, native-module reporting and useful compatibility status. Executing native DLL/SO modules is intentionally unsupported and is not a TODO.

## Fidelity and legacy compatibility

- [ ] **34. Remaining renderer features:** adaptive patch detail/stitching, flares, projected model shadows, text/shadow deformations, animated portal cameras and movie textures.
- [ ] **35. Original visual fidelity:** animation, model/dynamic lighting, gamma, fog, procedural noise and broader screenshot/demo comparisons.
- [ ] **36. Original audio fidelity:** attenuation, Doppler, processing and more devices.
- [ ] **37. Remaining cinematics:** startup intro policy, other campaign transitions, retail UI extensions and broader movie/mod timing.
- [ ] **38. Older demos:** protocol-43 playback and wider presentation fidelity. Protocol 68 recording/playback is implemented.
- [ ] **39. Filesystem compatibility:** multiple home/base/CD roots, archive rescans, legacy listing/existence APIs and unusual archive layouts.
- [ ] **40. Remaining UI/asset services:** CD-key handling and expansion/mod font, text and localization requirements.
- [ ] **41. Release validation:** more GPUs/drivers/operating systems, clean installs, sustained malformed-input testing and dependency/license documentation.

## Current user-reported repair

- [x] **Bridge console entry:** entering `/q3 bridge` through Minecraft chat must retain the bridge screen; backtick must open a working console, `give all` must grant original weapons, and model commands must update the visible character. Verified through actual chat submission on Vulkan in both retail and modern VMs, including fresh entry, original weapon commands, Visor selection and native-mode restoration. See `VALIDATION.md`.

See [compatibility](../COMPATIBILITY.md), [bridge limits](MINECRAFT_BRIDGE.md),
[building limits](MINECRAFT_BUILDING.md), [validation evidence](VALIDATION.md), and
[the complete original roadmap](../ROADMAP.md).
