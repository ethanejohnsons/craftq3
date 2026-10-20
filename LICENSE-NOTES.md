# License and provenance notes

CraftQ3 is licensed under the GNU General Public License version 3 (`LICENSE`); mod metadata
declares `GPL-3.0-only`. Changing that to "or later" or another license is a decision for the
project owner.

## Provenance

CraftQ3 is an independent implementation. No id Software or ioquake3 engine code is copied,
translated or linked into the runtime. Behaviour was derived from:

- public format descriptions (BSP, MD3, shader manual, RoQ, ZIP, TGA, JPEG, WAV) and the public
  game-module headers (`g_public.h`, `cg_public.h`, `ui_public.h`, `q_shared.h`, `botlib.h`) for
  numeric import identifiers, flags and struct layouts;
- observation of the user's own data and of an unmodified upstream ioquake3 build, used outside
  the repository as a differential oracle during the earlier implementation (the measured Huffman
  mappings and native message captures in `engine:net` tests come from that work);
- reading upstream sources as behavioural references, not as code to port.

The rewrite on this branch (2026-10) restructured that implementation into the current modules
and ported its algorithms with review; the porting rules forbid translating id-derived source
(`docs/PORTING.md`). This is independent implementation, not a formally segregated clean-room
process. Any future adapted code must record its source, copyright notice and license before it
is merged.

## Game data

Never commit or bundle commercial PK3, BSP, MD3, QVM, texture, audio, demo or screenshot assets,
or values copied out of them. Users supply their own data and point the mod at their
installation. Real-data tests read an installation named by `CRAFTQ3_Q3ROOT` and write only to
ignored `build/` directories. Synthetic fixtures in this repository are original test material.
Native Quake modules (`.so`, `.dll`, `quake3.exe`) are never loaded.

## Dependencies

Fabric Loader and Fabric API are Apache-2.0; other build and runtime dependencies keep their own
licenses. Minecraft and the JDK are licensed separately; nothing here grants rights to
redistribute Minecraft or Quake III assets. Quake III Arena and Minecraft are trademarks of their
owners; CraftQ3 is not an official id Software, Mojang, Microsoft or Fabric product.
