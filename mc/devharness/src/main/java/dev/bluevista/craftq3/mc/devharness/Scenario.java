package dev.bluevista.craftq3.mc.devharness;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A GameTest that runs named steps one after another, each polled once per server tick until it
 * reports done or its tick budget runs out. Lets one test walk through a long scenario (bridge
 * entry, movement, combat, leaving) on shared state without racing other tests.
 */
final class Scenario {
  private static final Logger LOGGER = LoggerFactory.getLogger(Scenario.class);

  /** One step: called every tick with the ticks spent in it so far; true when finished. */
  @FunctionalInterface
  interface Step {
    boolean tick(int ticks) throws Exception;
  }

  private record Named(String name, int budget, Step step) {}

  private final GameTestHelper helper;
  private final String name;
  private final List<Named> steps = new ArrayList<>();
  private final List<String> report = new ArrayList<>();
  private Runnable cleanup = () -> {};
  private int index;
  private int ticks;
  private boolean finished;

  Scenario(GameTestHelper helper, String name) {
    this.helper = helper;
    this.name = name;
  }

  Scenario step(String stepName, int budget, Step step) {
    steps.add(new Named(stepName, budget, step));
    return this;
  }

  /** Runs once when the scenario ends, passed or failed. */
  Scenario cleanup(Runnable action) {
    cleanup = action;
    return this;
  }

  void note(String line) {
    report.add(line);
    LOGGER.info("[{}] {}", name, line);
  }

  List<String> report() {
    return report;
  }

  /** Starts polling; the test succeeds after the last step. */
  void start() {
    helper.onEachTick(this::tick);
  }

  private void tick() {
    if (finished) return;
    if (index >= steps.size()) {
      finish(null);
      return;
    }
    Named current = steps.get(index);
    boolean done;
    try {
      done = current.step().tick(ticks++);
    } catch (Throwable failure) {
      finish(
          failure instanceof AssertionError || failure instanceof RuntimeException
              ? failure
              : new RuntimeException(failure));
      return;
    }
    if (done) {
      note("step '" + current.name() + "' passed after " + ticks + " ticks");
      index++;
      ticks = 0;
    } else if (ticks > current.budget()) {
      finish(
          new AssertionError(
              "step '" + current.name() + "' did not finish in " + current.budget() + " ticks"));
    }
  }

  private void finish(Throwable failure) {
    finished = true;
    try {
      cleanup.run();
    } catch (RuntimeException e) {
      LOGGER.warn("[{}] cleanup failed", name, e);
    }
    if (failure == null) {
      helper.succeed();
      return;
    }
    LOGGER.error("[{}] failed", name, failure);
    helper.fail(name + ": " + failure.getMessage());
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
