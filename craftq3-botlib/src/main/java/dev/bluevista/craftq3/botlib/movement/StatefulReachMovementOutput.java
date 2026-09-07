package dev.bluevista.craftq3.botlib.movement;

/** A measured reach executor result that also updates the active jump reachability. */
public interface StatefulReachMovementOutput extends ReachMovementOutput {
  int jumpReach();
}
