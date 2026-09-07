# Quake cinematics

Open **Cinematics** in the original Quake main menu and select an unlocked movie.
The original UI supplies the movie command and its return-menu continuation.
Movies read directly from your installed PK3s.

The Quake console also accepts:

```text
cinematic idlogo
cinematic demoend 1
cinematic demoend 2
```

The optional argument is `0` for normal playback, `1` to hold the final frame, or
`2` to loop. Names without a directory use `video/`; `.roq` is optional.
Escape, Space, Enter, other ordinary character keys or primary click skip playback.
Arrow keys and the wheel are consumed without moving a player. Backtick opens the
console; movie playback continues. Resizing fills the current framebuffer.

Starting a valid system movie closes the current Quake match or connection. It
does not suspend a match for later resumption. At EOF or a skip, `nextmap` executes
once and is cleared. The original Cinematics menu uses this to return to itself;
scripts can instead set a destination such as `set nextmap "map q3dm17"`.
Disconnect, explicit replacement and session shutdown cancel the movie without
running its continuation. A missing file or invalid opening frame leaves the
current match/movie intact. Runtime decode failures report an error and return to
the menu without running `nextmap`.

Movie frames use Blaze3D and work on the tested Vulkan backend. A bounded worker
decodes the soundtrack independently of presentation, and Minecraft's sound
executor refills native buffers independently of rendering. EOF, cancellation and
session close release movie textures and audio sources.

Both original retail and 1.32 UI profiles pass menu launch, skip and continuation
checks. The production application also passes EOF, hold, loop, resize, failed
replacement and return-to-game checks. Native Minecraft 26.2/Java 25/Vulkan testing
plays the complete idlogo soundtrack, follows `nextmap` into an original arena,
launches from the original menu and skips through the real screen input handler.
Both profiles also pass the production tutorial victory, automatic Tier 1 movie,
original next-arena selection and saved-progress reload. That campaign audit uses
a CPU audio sink; native playback timing is covered separately above.
See [validation](VALIDATION.md) for exact evidence and build details.

Shader `videoMap`, automatic startup intro policy, remaining campaign-triggered
movie transitions, retail UI extension syscalls and broader mod/movie timing remain
open.
