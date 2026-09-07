# Format implementation notes

Parsers are host-independent, bounded and tested with synthetic fixtures. Asset names resolve through the virtual filesystem, never through extraction or generated resource packs. Original game assets are not included.

- [PK3 and virtual paths](PK3.md)
- [BSP v46](BSP46.md)
- [Q3 shaders and TGA/JPEG images](SHADERS.md)
- [MD3 models, skins and animation configuration](MD3.md)
- [Collision and composable traces](COLLISION.md)
- [QVM bytecode and execution boundary](QVM.md)
- [WAV sound assets](WAV.md)
- [Projected BSP mark fragments](MARKS.md)
- [AAS navigation files](AAS.md)
- [Local snapshots](SNAPSHOTS.md)
- [Original UI VM](UI.md)
- [Protocol-68 datagrams and compressed messages](NETWORK68.md)
- [Demo record framing](DEMOS.md)

Gameplay network messages, demo playback and RoQ remain later work. Quake I and Quake II are not compatibility targets.
