package dev.bluevista.craftq3.engine.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.console.CommandBuffer.When;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class CommandBufferTest {
  // Ported from legacy EngineServicesTest (frameBoundary became breakFrame; NOW no longer splits).
  @Test
  void breakFrameDefersTheRemainderToTheNextFrame() {
    for (When when : List.of(When.INSERT, When.APPEND)) {
      var t = new TestConsole();
      t.commands()
          .register(
              "change_map",
              args -> {
                t.output.add("map requested");
                t.buffer().breakFrame();
              });
      t.buffer().add("echo queued\n");
      t.buffer().execute(when, "change_map; echo after\n");
      t.buffer().execute();
      assertFalse(t.output.contains("after"), when.toString());
      t.output.add("new map ready");
      t.buffer().execute();
      assertTrue(t.output.indexOf("after") > t.output.indexOf("new map ready"));
      assertEquals(1, t.output.stream().filter("after"::equals).count());
      assertTrue(t.output.contains("queued"));
    }
    var t = new TestConsole();
    t.commands().register("change_map", args -> t.buffer().breakFrame());
    t.buffer().execute(When.NOW, "change_map");
    t.buffer().add("echo next\n");
    t.buffer().execute();
    assertEquals(List.of("next"), t.output);
  }

  // Ported from legacy EngineServicesTest (second half of
  // commandsRespectQuotesCommentsInsertionAndWait).
  @Test
  void execInsertsBeforeTheRestAndWaitResumesFramesLater() {
    var t = new TestConsole().file("autoexec.cfg", "echo config; wait 2; echo resumed");
    t.buffer().add("echo before; exec autoexec; echo after\n");
    t.buffer().execute();
    assertEquals(List.of("before", "execing autoexec", "config"), t.output);
    t.buffer().execute();
    assertEquals(3, t.output.size());
    t.buffer().execute();
    assertEquals(List.of("before", "execing autoexec", "config", "resumed", "after"), t.output);
  }

  // Ported from legacy EngineServicesTest.
  @Test
  void recursiveConfigCannotConsumeAnUnboundedFrame() {
    var t = new TestConsole().file("loop.cfg", "exec loop");
    t.buffer().add("exec loop\n");
    assertEquals(20, t.buffer().execute(20));
    assertFalse(t.buffer().isEmpty());
    assertEquals(20, t.buffer().execute(20));
  }

  // Ported from legacy EngineServicesTest; NOW runs exactly one command as in 1.32.
  @Test
  void immediateCommandsPreserveBufferedCommandsAndFrameWait() {
    var t = new TestConsole().file("test.cfg", "echo config");
    t.buffer().add("wait 2; echo buffered\n");
    t.buffer().execute(When.NOW, "echo now");
    t.buffer().execute(When.NOW, "exec test");
    assertEquals(List.of("now", "execing test"), t.output);
    t.buffer().execute();
    assertEquals(List.of("now", "execing test", "config"), t.output);
    t.buffer().execute(When.NOW, "echo whilewaiting");
    assertEquals(List.of("now", "execing test", "config", "whilewaiting"), t.output);
    assertEquals(0, t.buffer().execute());
    t.buffer().execute();
    assertEquals(List.of("now", "execing test", "config", "whilewaiting", "buffered"), t.output);
  }

  // Ported from legacy EngineServicesTest.
  @Test
  void nestedImmediateDispatchRestoresCurrentCommand() {
    var t = new TestConsole();
    t.commands()
        .register(
            "outer",
            args -> {
              t.buffer().execute(When.NOW, "echo nested");
              assertSame(args, t.commands().current());
            });
    t.buffer().add("outer; echo later\n");
    assertEquals(1, t.buffer().execute(1));
    assertEquals(List.of("nested"), t.output);
    assertFalse(t.buffer().isEmpty());
    t.buffer().execute(1);
    assertEquals(List.of("nested", "later"), t.output);
    t.commands().register("recursive", args -> t.buffer().execute(When.NOW, "recursive"));
    t.buffer().execute(When.NOW, "recursive");
    assertTrue(t.output.getLast().contains("recursion limit"));
    assertEquals(CommandArgs.EMPTY, t.commands().current());
  }

  @Test
  void immediateTextIsOneCommandAndEmptyTextRunsTheBuffer() {
    var t = new TestConsole();
    t.buffer().execute(When.NOW, "echo a; echo b");
    assertEquals(List.of("a; echo b"), t.output);
    t.buffer().add("echo queued\n");
    t.buffer().execute(When.NOW, "");
    assertEquals(List.of("a; echo b", "queued"), t.output);
  }

  @Test
  void waitCountsFramesAndPersistsAcrossAnEmptyBuffer() {
    var t = new TestConsole();
    t.run("echo a; wait; echo b");
    assertEquals(List.of("a"), t.output);
    t.buffer().execute();
    assertEquals(List.of("a", "b"), t.output);

    t.output.clear();
    t.run("wait 3; echo c");
    for (int frame = 0; frame < 2; frame++) assertEquals(0, t.buffer().execute());
    assertEquals(List.of(), t.output);
    t.buffer().execute();
    assertEquals(List.of("c"), t.output);

    // A trailing wait delays whatever is added next, as in the original.
    t.output.clear();
    t.run("wait");
    t.buffer().add("echo late\n");
    t.buffer().execute();
    assertEquals(List.of(), t.output);
    t.buffer().execute();
    assertEquals(List.of("late"), t.output);
  }

  @Test
  void waitArgumentsUseAtoiAndAreClamped() {
    var t = new TestConsole();
    t.run("wait junk");
    assertEquals(0, t.buffer().waitFrames());
    t.run("wait -5");
    assertEquals(0, t.buffer().waitFrames());
    t.buffer().waitFrames(1 << 30);
    assertEquals(CommandBuffer.MAX_WAIT, t.buffer().waitFrames());
  }

  @Test
  void vstrRunsAheadOfTheRestOfTheLine() {
    var t = new TestConsole();
    t.cvars().set("v", "echo 2; echo 2b");
    t.run("echo 1; vstr v; echo 3");
    assertEquals(List.of("1", "2", "2b", "3"), t.output);
  }

  @Test
  void selfFeedingVstrIsBoundedPerFrame() {
    var t = new TestConsole();
    t.cvars().set("loop", "echo x; vstr loop");
    assertEquals(CommandBuffer.DEFAULT_FRAME_LIMIT, t.run("vstr loop"));
    assertFalse(t.buffer().isEmpty());
    assertEquals(CommandBuffer.DEFAULT_FRAME_LIMIT, t.buffer().execute());

    // A doubling loop eventually overflows the buffer and the overflowing text is dropped.
    var doubling = new TestConsole(false);
    doubling.cvars().set("a", "vstr a; vstr a");
    doubling.buffer().add("vstr a\n");
    for (int frame = 0;
        frame < 64 && !doubling.output.contains("Cbuf_InsertText overflowed");
        frame++) doubling.buffer().execute();
    assertTrue(doubling.output.contains("Cbuf_InsertText overflowed"));
    assertTrue(doubling.buffer().length() <= CommandBuffer.CAPACITY);
  }

  @Test
  void addAppendsVerbatimAndOverflowIsDropped() {
    var t = new TestConsole();
    t.buffer().add("echo a");
    t.buffer().add("b\n");
    t.buffer().execute();
    assertEquals(List.of("ab"), t.output);
    assertFalse(t.buffer().add("x".repeat(CommandBuffer.CAPACITY + 1)));
    assertEquals("Cbuf_AddText: overflow", t.output.getLast());
    assertTrue(t.buffer().isEmpty());
  }

  @Test
  void nestedExecuteDrainsTheSameBufferWithTheSameBound() {
    var t = new TestConsole();
    t.commands().register("flush", args -> t.buffer().execute());
    t.buffer().add("flush\necho 1\necho 2\necho 3\n");
    assertEquals(3, t.buffer().execute(3));
    assertEquals(List.of("1", "2"), t.output);
    t.buffer().execute();
    assertEquals(List.of("1", "2", "3"), t.output);
  }

  @Test
  void bufferRejectsOtherThreadsOnceOwned() throws Exception {
    var t = new TestConsole();
    t.console.owner().claim();
    try {
      var failure =
          assertThrows(
              ExecutionException.class,
              () -> CompletableFuture.runAsync(() -> t.buffer().add("echo x\n")).get());
      assertTrue(failure.getCause() instanceof IllegalStateException);
      assertTrue(t.buffer().isEmpty());
    } finally {
      t.console.owner().release();
    }
  }
}
