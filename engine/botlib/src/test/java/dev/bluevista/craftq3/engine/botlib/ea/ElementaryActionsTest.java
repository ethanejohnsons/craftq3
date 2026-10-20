package dev.bluevista.craftq3.engine.botlib.ea;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElementaryActionsTest {
  private final List<String> commands = new ArrayList<>();
  private final ElementaryActions actions =
      new ElementaryActions(4, (client, text) -> commands.add(client + ":" + text));

  @Test
  void acceptsAbsoluteLevelTimesBeyondAnHour() {
    // The original qagame passes level time in seconds; a soak test ran past 3600.
    assertEquals(7200.5f, actions.getInput(0, 7200.5f).thinkTime());
    actions.endRegular(0, 86400);
  }

  @Test
  void combinesActionsAndOverwritesMovementWithoutNormalizingIt() {
    actions.attack(0);
    actions.use(0);
    actions.respawn(0);
    actions.crouch(0);
    actions.walk(0);
    actions.moveUp(0);
    actions.moveDown(0);
    actions.moveForward(0);
    actions.moveBack(0);
    actions.moveLeft(0);
    actions.moveRight(0);
    actions.talk(0);
    actions.gesture(0);
    actions.action(
        0,
        ActionFlags.AFFIRMATIVE
            | ActionFlags.NEGATIVE
            | ActionFlags.GET_FLAG
            | ActionFlags.GUARD_BASE
            | ActionFlags.PATROL
            | ActionFlags.FOLLOW_ME);
    actions.move(0, new Vec3f(3, 4, 0), 900);
    var input = actions.getInput(0, 0.25f);
    assertEquals(400, input.speed());
    assertEquals(new Vec3f(3, 4, 0), input.direction());
    assertEquals(0.25f, input.thinkTime());
    assertEquals(0x0bbb3bab, input.actionFlags());
    actions.move(0, new Vec3f(0, 0, -2), -900);
    assertEquals(-400, actions.getInput(0, 0).speed());
    assertEquals(new Vec3f(0, 0, -2), actions.getInput(0, 0).direction());
    actions.action(0, 0x44444444);
    assertEquals(input.actionFlags() | 0x44444444, actions.getInput(0, 0).actionFlags());
  }

  @Test
  void resetPreservesViewAndOpaqueWeaponButClearsRegularState() {
    actions.view(0, new Vec3f(-720, 721, 450));
    actions.selectWeapon(0, -123);
    actions.move(0, new Vec3f(3, 4, 0), 100);
    actions.attack(0);
    var previous = actions.getInput(0, 0.2f);
    actions.resetInput(0);
    var input = actions.snapshot(0);
    assertEquals(previous.viewAngles(), input.viewAngles());
    assertEquals(-123, input.weapon());
    assertEquals(new Vec3f(0, 0, 0), input.direction());
    assertEquals(0, input.speed());
    assertEquals(0, input.actionFlags());
    assertEquals(0, input.thinkTime());
    assertEquals(100, previous.speed());
    assertTrue(previous.hasAction(ActionFlags.ATTACK));
  }

  @Test
  void jumpHistorySuppressesTheFollowingFrameThenAllowsAnotherJump() {
    actions.jump(0);
    actions.jump(0);
    assertEquals(ActionFlags.JUMP, actions.snapshot(0).actionFlags());
    actions.resetInput(0);
    assertEquals(ActionFlags.JUMPED_LAST_FRAME, actions.snapshot(0).actionFlags());
    actions.jump(0);
    actions.delayedJump(0);
    actions.jump(0);
    assertEquals(ActionFlags.JUMPED_LAST_FRAME, actions.snapshot(0).actionFlags());
    actions.resetInput(0);
    actions.jump(0);
    assertEquals(ActionFlags.JUMP, actions.snapshot(0).actionFlags());
    actions.action(0, ActionFlags.JUMPED_LAST_FRAME | ActionFlags.DELAYED_JUMP);
    actions.jump(0);
    assertEquals(
        ActionFlags.JUMPED_LAST_FRAME | ActionFlags.DELAYED_JUMP,
        actions.snapshot(0).actionFlags());
    actions.delayedJump(0);
    assertEquals(ActionFlags.JUMPED_LAST_FRAME, actions.snapshot(0).actionFlags());
  }

  @Test
  void delayedJumpIsNotConvertedAndDoesNotLatchJumpHistory() {
    actions.delayedJump(0);
    assertEquals(ActionFlags.DELAYED_JUMP, actions.getInput(0, 0.1f).actionFlags());
    actions.endRegular(0, 0.5f);
    assertEquals(ActionFlags.DELAYED_JUMP, actions.snapshot(0).actionFlags());
    assertEquals(0.1f, actions.snapshot(0).thinkTime());
    assertTrue(commands.isEmpty());
    actions.resetInput(0);
    assertEquals(0, actions.snapshot(0).actionFlags());
    actions.delayedJump(0);
    actions.jump(0);
    assertEquals(ActionFlags.DELAYED_JUMP | ActionFlags.JUMP, actions.snapshot(0).actionFlags());
    actions.resetInput(0);
    assertEquals(ActionFlags.JUMPED_LAST_FRAME, actions.snapshot(0).actionFlags());
  }

  @Test
  void clientStateIsIsolatedAndSlotClearAndCloseHaveExplicitLifetimes() {
    actions.view(0, new Vec3f(1, 2, 3));
    actions.selectWeapon(0, 7);
    actions.jump(0);
    assertEquals(0, actions.snapshot(1).weapon());
    assertEquals(0, actions.snapshot(1).actionFlags());
    actions.attack(1);
    actions.clearClient(0);
    assertEquals(new Vec3f(0, 0, 0), actions.snapshot(0).viewAngles());
    assertEquals(0, actions.snapshot(0).weapon());
    assertEquals(ActionFlags.ATTACK, actions.snapshot(1).actionFlags());
    actions.close();
    actions.close();
    assertThrows(IllegalStateException.class, () -> actions.attack(0));
    assertThrows(IllegalStateException.class, () -> actions.command(0, "team red"));
    assertTrue(commands.isEmpty());
  }

  @Test
  void commandCallbacksPreserveNativePrefixesAndLiteralText() {
    actions.say(0, "hello world");
    actions.sayTeam(1, "quoted \"hello\"; still text");
    actions.command(2, "team red");
    assertEquals(
        List.of("0:say hello world", "1:say_team quoted \"hello\"; still text", "2:team red"),
        commands);
    actions.command(3, "é");
    assertEquals("3:é", commands.getLast());
    actions.say(0, "x".repeat(1019));
    assertEquals(1025, commands.getLast().length());
    int before = commands.size();
    assertThrows(IllegalArgumentException.class, () -> actions.say(0, "x".repeat(1020)));
    assertThrows(IllegalArgumentException.class, () -> actions.command(0, "bad\0text"));
    assertThrows(IllegalArgumentException.class, () -> actions.command(0, "😃"));
    assertEquals(before, commands.size());
  }

  @Test
  void invalidValuesDoNotPartiallyMutateInputs() {
    assertThrows(IllegalArgumentException.class, () -> new ElementaryActions(0, (c, s) -> {}));
    assertThrows(IllegalArgumentException.class, () -> new ElementaryActions(65, (c, s) -> {}));
    assertThrows(IllegalArgumentException.class, () -> actions.attack(-1));
    assertThrows(IllegalArgumentException.class, () -> actions.attack(4));
    actions.move(0, new Vec3f(1, 2, 3), 50);
    var previous = actions.snapshot(0);
    assertThrows(
        IllegalArgumentException.class, () -> actions.move(0, new Vec3f(4, 5, 6), Float.NaN));
    assertThrows(
        IllegalArgumentException.class, () -> actions.move(0, new Vec3f(1000001, 0, 0), 100));
    assertThrows(IllegalArgumentException.class, () -> actions.view(0, new Vec3f(1e10f, 0, 0)));
    assertThrows(IllegalArgumentException.class, () -> actions.getInput(0, Float.NaN));
    assertThrows(
        IllegalArgumentException.class, () -> actions.endRegular(0, Float.POSITIVE_INFINITY));
    assertEquals(previous, actions.snapshot(0));
  }
}
