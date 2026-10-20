package dev.bluevista.craftq3.mc.hybrid.terrain;

import dev.bluevista.craftq3.engine.collision.Contents;

/** Minecraft fluids and the Quake contents they report. */
public enum FluidKind {
  WATER(Contents.WATER),
  LAVA(Contents.LAVA),
  /** Not a vanilla fluid; for modded fluids that should behave like Quake slime. */
  SLIME(Contents.SLIME);

  private final int contents;

  FluidKind(int contents) {
    this.contents = contents;
  }

  /** The {@code CONTENTS_*} bit the fluid's volume reports. */
  public int contents() {
    return contents;
  }

  /** The fluid whose contents bit is {@code contents}, or null. */
  public static FluidKind ofContents(int contents) {
    for (FluidKind kind : values()) if (kind.contents == contents) return kind;
    return null;
  }
}
