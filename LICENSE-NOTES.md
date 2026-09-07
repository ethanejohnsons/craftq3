# License and provenance notes

The repository already contained the GNU General Public License version 3 text in `LICENSE` before this implementation. It is preserved. Mod metadata identifies the existing project license as `GPL-3.0-only`; this does not assert that CraftQ3 is derived from ioquake3. If the owner intends an “or later” grant or another license, that requires an explicit project licensing decision, not an inferred change by the implementation.

The filesystem, parsers, mesh builder, camera and backend code in this pass were independently written. No ioquake3 functions were translated or copied. Public descriptions of file formats and engine behavior were used; ioquake3 filesystem source was consulted as a behavioral reference. This is independent implementation, not a claim of a formally segregated clean-room process. Any future copied/adapted implementation must record its source, copyright notices and compatible licensing before merging. Do not mechanically port engine code merely because this repository already has a GPL file.

## References used

- [PKWARE APPNOTE](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT): ZIP32 footer metadata layout.

- [Kekoa Proudfoot's Unofficial Quake 3 Map Specs](https://www.mralligator.com/q3/): BSP46 layout and patch interpretation; the documentation is copyrighted and was not copied into source.
- [ioquake3 filesystem reference](https://github.com/ioquake/ioq3/blob/master/code/qcommon/files.c): behavioral reference for game and pack ordering, not source for Java implementation.
- [Fabric 26.2 release guidance](https://www.fabricmc.net/2026/06/15/262.html) and [rendering concepts](https://docs.fabricmc.net/develop/rendering/basic-concepts): supported host APIs and Vulkan requirement.
- Pinned Minecraft/Fabric API signatures and local Java bytecode inspection: integration details. Minecraft sources/assets remain dependency caches, not repository content.

Fabric API is Apache-2.0, Fabric Loader is Apache-2.0, and other build/runtime dependencies retain their respective licenses. This note is not an exhaustive redistributed dependency license inventory. Java/JDK and Minecraft licensing remain separate from CraftQ3. No permission to redistribute Minecraft or commercial Quake assets is granted by this project's license.

Never commit or bundle commercial PK3, BSP, MD3, QVM, texture, audio, demo-reference or screenshot assets. Users supply their own legitimate data and point the mod at their installation. `.gitignore` excludes typical asset formats and local game folders. Synthetic fixture bytes and shaders in this repository are original testing/debug material; generated fixtures stay in the ignored run directory.

Quake III and Minecraft names/trademarks belong to their respective owners. CraftQ3 is an independent project, not an official id Software, Mojang, Microsoft or Fabric product. Arbitrary native Quake DLL/SO modules are intentionally unsupported.

## Shader increment references

The original Java implementations also consulted the [id/Q3Radiant shader manual](https://icculus.org/gtkradiant/documentation/Q3AShader_Manual/), [portal entity documentation](https://icculus.org/gtkradiant/documentation/q3radiant_manual/appndx/appn_b_6.htm), and upstream ioquake3 `tr_image.c`, `tr_bsp.c`, `tr_light.c` and `tr_sky.c` as behavioral references for overbright restoration, fog curves, grid alignment, direction encoding and unused inner-skybox behavior. No engine functions were copied or mechanically translated. Image decoding follows published TGA/JPEG formats; JPEG uses the JDK ImageIO provider.

For local visual validation only, unchanged official ioquake3 commit `588393618dbc82e7207c21c6ddecca229944a03a` was built with its supported standalone option under ignored `.tools/ioquake3-source`, allowing the user's pak0-only assets to be compared without patch-pack overrides. Oracle binaries/source and captures are not dependencies, are not bundled with CraftQ3, and remain outside tracked source. The oracle retains its upstream GPL notices; local download/build provenance is recorded under `.tools/ioquake3-oracle/PROVENANCE.md`.

## Simulation and VM increment

The new Java collision, MD3, QVM and engine-service code is independently implemented. Public ABI declarations in [g_public.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/g_public.h), [q_shared.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/game/q_shared.h), and [qcommon.h](https://github.com/id-Software/Quake-III-Arena/blob/master/code/qcommon/qcommon.h) supply numeric syscall IDs, file/struct layouts, flags and calling conventions. A local C `sizeof`/`offsetof` probe checked those declarations; no engine function was used in the Java runtime. Format-specific references and behavioral limits are recorded in `docs/formats/`.

Local QVM validation reads the user's original bytecode through the PK3 VFS and executes it in the checked Java interpreter. Original VM bytes, disassemblies, native oracle builds and captured game data remain ignored. Synthetic unit-test bytecode and input fixtures are newly authored.

An additional QA-only modern qagame QVM was built from the unchanged ioquake3 commit above with the supported `BUILD_GAME_QVMS=ON` target and native game modules/client/server disabled. The local artifact's SHA-256 is `42f9a7b648b9ee504364e4ba3d868ab092fd94545eb0c05b44a79217990fbb9a`. The gameplay audit can substitute these bytecode bytes without modifying the user's packs or mount. An upstream assembler symbol map was used to identify a cvar-handle compatibility fault; neither that symbol map nor upstream assembly is shipped. Public key-code/button declarations and the observed zero-based cvar-handle contract informed independent input/service code. No native engine is embedded or invoked by CraftQ3 gameplay.

The same supported QA-only build produced modern cgame bytecode with SHA-256 `12d597a49bc351149d7459692a0311cc5e186cf4f376c703ddaa6cfa27a602e4`. Both retail and modern cgame audits execute in the Java interpreter. Numeric ABI declarations and observed behavior informed the independently written snapshot, model, audio and HUD adapters; upstream game/engine implementations are not shipped.

The corresponding modern UI oracle has SHA-256 `23ba9181726e108be05a0096a9f49f3c7643d4ff8888267a6948c4a4e8389c33`. The retail UI host also validates its older import table and API-3 key-event convention from the original bytecode. The original menu strings, artwork, models and bytecode remain exclusively in user-supplied packs.

The independent protocol-68 codec uses published packet layouts and 256 Huffman wire mappings measured through unchanged native message functions. The embedded mappings are protocol observations, not a copied upstream tree/frequency implementation. The authored `NetchanOracle.c` driver links ignored, unchanged upstream objects for differential testing only. Source revision, observation hash, strict-input differences and reproduction are documented in `docs/formats/NETWORK68.md`. No upstream native networking/compression implementation is embedded in the Java runtime.

The in-progress independent RoQ parser uses [Tim Ferguson’s 2001 format description](https://multimedia.cx/mirror/idroq.txt), not native decoder source. It is not connected to playback yet; see [decoder status](docs/formats/ROQ.md).


### Minecraft incoming-damage adapter

The bridge creates geometry-free inline descriptors and original `trigger_hurt`
entity records using the public Q3Radiant map-entity contract. It temporarily
exposes host contact through engine collision syscalls. The supplied original
qagame executes damage, armor, death and respawn. No native game routine was
ported into this adapter, no private gentity damage/health offsets are patched,
and no original PK3 is changed. Both retail and modern QVM behavior is exercised
by `scripts/AuditBridgeCombat.java`; Fabric mixins integrate Minecraft's own
attack acceptance with the host damage queue.
