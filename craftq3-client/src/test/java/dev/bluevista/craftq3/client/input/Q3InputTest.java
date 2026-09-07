package dev.bluevista.craftq3.client.input;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Q3InputTest {
  private static Q3Input input() {
    var cvars = new CvarSystem();
    var fs =
        new VirtualFileSystem() {
          public Optional<Origin> which(VirtualPath path) {
            return Optional.empty();
          }

          public List<VirtualPath> list(String directory) {
            return List.of();
          }

          public List<Origin> searchOrder() {
            return List.of();
          }

          public byte[] read(VirtualPath path) throws FileNotFoundException {
            throw new FileNotFoundException(path.value());
          }

          public void close() {}
        };
    return new Q3Input(cvars, new CommandSystem(cvars, fs, message -> {}), 1000);
  }

  @Test
  void integratesBriefMovementAndLatchesAttackAcrossSamples() {
    var input = input();
    input.key('w', true, 1002);
    input.key('w', false, 1006);
    input.key(Q3Input.MOUSE1, true, 1003);
    input.key(Q3Input.MOUSE1, false, 1005);
    var command = input.sample(1008, 2, 1, 0);
    assertEquals(63, command.forward());
    assertEquals(1, command.buttons() & 1);
    assertEquals(2, command.weapon());
    var next = input.sample(1016, 2, 1, 0);
    assertEquals(0, next.forward());
    assertEquals(0, next.buttons() & 1);
  }

  @Test
  void twoBindingsForOneActionSurviveIndividualReleaseAndRebind() {
    var input = input();
    input.bindings().bind('e', "+forward");
    input.key('w', true, 1000);
    input.key('e', true, 1000);
    input.key('w', false, 1004);
    input.bindings().bind('e', "+back");
    assertEquals(127, input.sample(1008, 2, 1, 0).forward());
    input.key('e', false, 1008);
    assertEquals(0, input.sample(1016, 2, 1, 0).forward());
  }

  @Test
  void walkModifierAndFocusLossDoNotLeaveActionsHeld() {
    var input = input();
    input.key('w', true, 1000);
    input.key(138, true, 1000);
    var walk = input.sample(1008, 2, 1, 0);
    assertEquals(64, walk.forward());
    assertEquals(16, walk.buttons() & 16);
    input.key(Q3Input.MOUSE1, true, 1008);
    input.mouse(100, 200);
    input.releaseAll(1010);
    var released = input.sample(1016, 2, 1, 0);
    assertEquals(0, released.forward());
    assertEquals(0, released.buttons());
    assertEquals(0, released.yaw());
    assertEquals(0, released.pitch());
  }

  @Test
  void mouseAnglesRemainInQuakeCoordinatesAndRespectDeltaPitch() {
    var input = input();
    input.mouse(100, 20);
    var command = input.sample(1008, 7, 1, 0);
    assertEquals(Q3Input.angleShort(-11), command.yaw());
    assertEquals(Q3Input.angleShort(2.2), command.pitch());
    input.mouse(0, 100000);
    var clamped = input.sample(1016, 7, 1, Q3Input.angleShort(45));
    double finalPitch = ((clamped.pitch() + Q3Input.angleShort(45)) & 65535) * 360.0 / 65536;
    assertTrue(finalPitch < 90 || finalPitch > 270);
    assertEquals(Q3Input.MOUSE1, Q3Input.keyNumber("MOUSE1"));
    assertEquals('w', Q3Input.keyNumber("W"));
  }
}
