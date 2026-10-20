# File and protocol formats

Reference notes for the Quake III file formats and wire protocols CraftQ3 reads and writes, with
the limits and leniency rules the current implementation applies. Each page links the classes
that implement it. Behaviour that deliberately differs from the original 1.32 engine is listed in
[DIVERGENCES.md](../DIVERGENCES.md); QVM imports are listed in [SYSCALLS.md](../SYSCALLS.md).

Parsers take bytes from the virtual filesystem (PK3 entries are read in place, never extracted),
check every offset and count, and throw a module-specific unchecked `*FormatException`. No game
data ships with the mod and none is used as a test fixture; see
[LICENSE-NOTES.md](../../LICENSE-NOTES.md) for provenance.

## Files

| Page | Format | Module |
| --- | --- | --- |
| [PK3.md](PK3.md) | PK3 archives, virtual paths, search order, pure checksums | `engine:fs` |
| [BSP46.md](BSP46.md) | IBSP maps (version 46, and 47) | `engine:formats` |
| [AAS.md](AAS.md) | AAS bot navigation files (versions 4 and 5) | `engine:formats` |
| [MD3.md](MD3.md) | MD3 models, skins, `animation.cfg` | `engine:formats` |
| [SHADERS.md](SHADERS.md) | Shader scripts, TGA and JPEG images, material programs | `engine:formats`, `engine:render-ir` |
| [WAV.md](WAV.md) | WAV sound files | `engine:formats` |
| [ROQ.md](ROQ.md) | RoQ cinematics | `engine:formats`, `engine:client` |
| [QVM.md](QVM.md) | QVM bytecode and the interpreter | `engine:vm`, `engine:vm-abi` |

## Engine services seen by the QVMs

| Page | Topic | Module |
| --- | --- | --- |
| [COLLISION.md](COLLISION.md) | Collision model, traces, patches, area portals | `engine:collision` |
| [MARKS.md](MARKS.md) | Mark fragments projected onto world geometry | `engine:render-ir` |
| [SNAPSHOTS.md](SNAPSHOTS.md) | Server snapshot entity selection | `engine:game`, `engine:client` |
| [CGAME.md](CGAME.md) | cgame host and struct layouts | `engine:client`, `engine:vm-abi` |
| [UI.md](UI.md) | ui host and struct layouts | `engine:client`, `engine:vm-abi` |

## Network and demos

| Page | Topic | Module |
| --- | --- | --- |
| [NETWORK68.md](NETWORK68.md) | Netchan framing, Huffman bit stream, strings, transports | `engine:net` |
| [CONNECTIONLESS68.md](CONNECTIONLESS68.md) | Out-of-band packets: challenge, connect, info, status, masters | `engine:net` |
| [NETWORK68_DELTA.md](NETWORK68_DELTA.md) | Entity, player and user-command deltas | `engine:net` |
| [NETWORK68_MESSAGES.md](NETWORK68_MESSAGES.md) | Server and client messages, snapshots, gamestate | `engine:net` |
| [DEMOS.md](DEMOS.md) | Demo files, protocols 43 to 68 | `engine:net`, `engine:client` |

Quake I, Quake II and Team Arena (`missionpack`) formats are not targets.
