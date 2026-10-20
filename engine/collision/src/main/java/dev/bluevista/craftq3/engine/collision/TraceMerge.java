package dev.bluevista.craftq3.engine.collision;

/**
 * The one rule for combining traces against several solids (the world, then each linked entity), as
 * the original server's entity clipping does:
 *
 * <ul>
 *   <li>an all-solid candidate makes the result all-solid; a start-solid one makes it start-solid;
 *   <li>a candidate replaces the result only when its fraction is strictly smaller, so the first of
 *       several equally near hits wins (stable ties);
 *   <li>a replacement keeps any start-solid flag the result already carried;
 *   <li>the candidate's entity number is recorded whenever it is solid or replaces the result.
 * </ul>
 *
 * <p>Brushes inside one collision model are merged by the same rule while tracing.
 */
public final class TraceMerge {
  private TraceMerge() {}

  /**
   * Merges {@code candidate}, the trace against entity {@code entityNum}, into {@code result}.
   * {@code candidate} is modified (its entity number is set), mirroring the original.
   *
   * @return true when {@code result} became all-solid, so further candidates cannot matter
   */
  public static boolean merge(Trace result, Trace candidate, int entityNum) {
    if (candidate.allSolid) {
      result.allSolid = true;
      candidate.entityNum = entityNum;
    } else if (candidate.startSolid) {
      result.startSolid = true;
      candidate.entityNum = entityNum;
    }
    if (candidate.fraction < result.fraction) {
      boolean previousStartSolid = result.startSolid;
      candidate.entityNum = entityNum;
      result.copyFrom(candidate);
      result.startSolid |= previousStartSolid;
    }
    return result.allSolid;
  }
}
