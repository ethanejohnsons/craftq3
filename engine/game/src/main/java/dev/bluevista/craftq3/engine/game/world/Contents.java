package dev.bluevista.craftq3.engine.game.world;

/** The {@code CONTENTS_*} bits and {@code SVF_*} server flags the server itself interprets. */
public final class Contents {
  public static final int SOLID = 1;
  public static final int LAVA = 8;
  public static final int SLIME = 16;
  public static final int WATER = 32;
  public static final int AREAPORTAL = 0x8000;
  public static final int PLAYERCLIP = 0x10000;
  public static final int BODY = 0x2000000;
  public static final int CORPSE = 0x4000000;
  public static final int TRIGGER = 0x40000000;

  /** {@code MASK_ALL}. */
  public static final int ALL = -1;

  /** {@code SVF_NOCLIENT}: never sent to any client. */
  public static final int SVF_NOCLIENT = 0x1;

  /** {@code SVF_CLIENTMASK}: sent only to the clients whose bit is set in {@code singleClient}. */
  public static final int SVF_CLIENTMASK = 0x2;

  /** {@code SVF_BOT}: set by the game on bot clients. */
  public static final int SVF_BOT = 0x8;

  /** {@code SVF_BROADCAST}: sent to every client regardless of visibility. */
  public static final int SVF_BROADCAST = 0x20;

  /** {@code SVF_PORTAL}: merge the PVS at {@code s.origin2} into snapshots. */
  public static final int SVF_PORTAL = 0x40;

  /** {@code SVF_SINGLECLIENT}: sent only to {@code singleClient}. */
  public static final int SVF_SINGLECLIENT = 0x100;

  /** {@code SVF_NOSERVERINFO}: no serverinfo configstring updates for this client. */
  public static final int SVF_NOSERVERINFO = 0x200;

  /** {@code SVF_CAPSULE}: clip against this entity as a capsule. */
  public static final int SVF_CAPSULE = 0x400;

  /** {@code SVF_NOTSINGLECLIENT}: sent to everyone except {@code singleClient}. */
  public static final int SVF_NOTSINGLECLIENT = 0x800;

  /** {@code SOLID_BMODEL}: {@code s.solid} of an inline brush model. */
  public static final int SOLID_BMODEL = 0xffffff;

  private Contents() {}
}
