package dev.bluevista.craftq3.engine.collision;

/**
 * Foreign collision geometry composed into world traces and point queries, for example terrain that
 * is not part of the BSP. Implementations must be safe to call from every thread that traces the
 * model and should not allocate per call.
 */
public interface CollisionSource {
  /** Clips every solid that may touch the swept volume through {@code clipper}. */
  void clip(TraceClipper clipper);

  /** The OR of the contents of every solid containing the world point. */
  int pointContents(float x, float y, float z);
}
