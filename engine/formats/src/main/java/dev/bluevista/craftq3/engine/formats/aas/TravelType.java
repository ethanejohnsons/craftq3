package dev.bluevista.craftq3.engine.formats.aas;

import java.util.Optional;

/** Base travel types stored in the low 24 bits of a reachability's travel field. */
public enum TravelType {
  INVALID(1),
  WALK(2),
  CROUCH(3),
  BARRIER_JUMP(4),
  JUMP(5),
  LADDER(6),
  WALK_OFF_LEDGE(7),
  SWIM(8),
  WATER_JUMP(9),
  TELEPORT(10),
  ELEVATOR(11),
  ROCKET_JUMP(12),
  BFG_JUMP(13),
  GRAPPLE_HOOK(14),
  DOUBLE_JUMP(15),
  RAMP_JUMP(16),
  STRAFE_JUMP(17),
  JUMP_PAD(18),
  FUNC_BOB(19);

  /** Mask of the base type within the raw travel field; the high byte holds travel flags. */
  public static final int TYPE_MASK = 0x00ff_ffff;

  private static final TravelType[] VALUES = values();

  private final int id;

  TravelType(int id) {
    this.id = id;
  }

  public int id() {
    return id;
  }

  public static Optional<TravelType> fromId(int id) {
    return id >= 1 && id <= VALUES.length ? Optional.of(VALUES[id - 1]) : Optional.empty();
  }

  /**
   * Whether the reachability's face and edge fields are geometry references. Elevators, jump pads
   * and bobbing platforms reuse them as travel data.
   */
  public static boolean hasGeometryReferences(int baseType) {
    return baseType >= WALK.id && baseType <= STRAFE_JUMP.id && baseType != ELEVATOR.id;
  }
}
