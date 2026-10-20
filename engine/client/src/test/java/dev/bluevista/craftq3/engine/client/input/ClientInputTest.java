package dev.bluevista.craftq3.engine.client.input;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.console.ConfigSink;
import dev.bluevista.craftq3.engine.console.ConfigSource;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.console.ConsoleOutput;
import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClientInputTest {
  private final Console console =
      Console.client(
          new ThreadOwner("input test"), ConsoleOutput.DISCARD, ConfigSource.NONE, ConfigSink.NONE);
  private final ClientInput input =
      new ClientInput(console.cvars(), console.buffer(), console.bindings().orElseThrow());

  ClientInputTest() {
    var bindings = console.bindings().orElseThrow();
    bindings.bind('w', "+forward");
    bindings.bind('s', "+back");
    bindings.bind(KeyNames.SHIFT, "+speed");
    bindings.bind(KeyNames.MOUSE1, "+attack");
    input.createCommand(1000, 1000, 0, 1, 0);
  }

  private void key(int key, boolean down, int time) {
    input.keyEvent(key, down, time);
    console.frame();
  }

  @Test
  void integratesBriefMovementAndLatchesAttackAcrossCommands() {
    key('w', true, 1002);
    key('w', false, 1006);
    key(KeyNames.MOUSE1, true, 1003);
    key(KeyNames.MOUSE1, false, 1005);
    UserCmd command = input.createCommand(1008, 1008, 2, 1, 0);
    assertEquals(63, command.forward(), "held 4 of 8 ms at run speed");
    assertEquals(ClientInput.BUTTON_ATTACK, command.buttons() & ClientInput.BUTTON_ATTACK);
    assertEquals(2, command.weapon());
    UserCmd next = input.createCommand(1016, 1016, 2, 1, 0);
    assertEquals(0, next.forward());
    assertEquals(0, next.buttons() & ClientInput.BUTTON_ATTACK);
  }

  @Test
  void twoKeysHoldOneButtonUntilBothRelease() {
    console.bindings().orElseThrow().bind('e', "+forward");
    key('w', true, 1000);
    key('e', true, 1000);
    key('w', false, 1004);
    // Rebinding a held key still releases the button it pressed.
    console.bindings().orElseThrow().bind('e', "+back");
    assertEquals(127, input.createCommand(1008, 1008, 0, 1, 0).forward());
    key('e', false, 1008);
    assertEquals(0, input.createCommand(1016, 1016, 0, 1, 0).forward());
  }

  @Test
  void speedInvertsRunningIntoWalking() {
    key('w', true, 1000);
    key(KeyNames.SHIFT, true, 1000);
    UserCmd walk = input.createCommand(1008, 1008, 0, 1, 0);
    assertEquals(64, walk.forward());
    assertEquals(ClientInput.BUTTON_WALKING, walk.buttons() & ClientInput.BUTTON_WALKING);
    console.cvars().set("cl_run", "0");
    key(KeyNames.SHIFT, false, 1008);
    assertEquals(64, input.createCommand(1016, 1016, 0, 1, 0).forward());
  }

  @Test
  void mouseTurnsWithSensitivityYawAndPitchAndClampsAgainstDeltaAngles() {
    input.mouseEvent(100, 20, 1001);
    UserCmd command = input.createCommand(1008, 1008, 0, 1, 0);
    // sensitivity 5 * m_yaw 0.022 * 100 = 11 degrees right; 5 * 0.022 * 20 = 2.2 down.
    assertEquals(ClientInput.angleToShort(-11), command.yaw());
    assertEquals(ClientInput.angleToShort(2.2), command.pitch());
    input.mouseEvent(0, 100000, 1010);
    UserCmd clamped = input.createCommand(1016, 1016, 0, 1, ClientInput.angleToShort(45));
    double pitch = ClientInput.shortToAngle(clamped.pitch() + ClientInput.angleToShort(45));
    assertTrue(pitch <= 90.01, "pitch " + pitch);
  }

  @Test
  void zoomScalesMouseSensitivity() {
    input.mouseEvent(100, 0, 1001);
    UserCmd command = input.createCommand(1008, 1008, 0, 0.5f, 0);
    assertEquals(ClientInput.angleToShort(-5.5), command.yaw());
  }

  @Test
  void catchersRouteKeysAndCharactersToTheirTargets() {
    List<String> ui = new ArrayList<>(), console = new ArrayList<>();
    input.setUiTarget(
        new KeyTarget() {
          @Override
          public void keyEvent(int key, boolean down, int time) {
            ui.add(key + (down ? "+" : "-"));
          }

          @Override
          public void mouseEvent(int dx, int dy, int time) {
            ui.add("mouse " + dx + " " + dy);
          }
        });
    input.setConsoleTarget((key, down, time) -> console.add(Integer.toString(key)));
    input.setCatchers(ClientInput.KEYCATCH_UI);
    key('w', true, 1000);
    key('w', false, 1001);
    input.charEvent('w', 1001);
    input.mouseEvent(3, 4, 1002);
    assertEquals(List.of("119+", "119-", (('w' | ClientInput.K_CHAR_FLAG)) + "+", "mouse 3 4"), ui);
    assertEquals(0, input.createCommand(1008, 1008, 0, 1, 0).forward(), "no binding fired");
    assertEquals(ClientInput.BUTTON_TALK, input.createCommand(1016, 1016, 0, 1, 0).buttons() & 2);
    input.keyEvent(ClientInput.KEY_CONSOLE, true, 1020);
    assertEquals(ClientInput.KEYCATCH_UI | ClientInput.KEYCATCH_CONSOLE, input.catchers());
    key('a', true, 1021);
    assertEquals(List.of("97"), console);
  }

  @Test
  void escapeClearsTheCgameCatcherThenOpensTheMenu() {
    List<String> hooks = new ArrayList<>();
    input.setHooks(
        new ClientInput.Hooks() {
          @Override
          public void escapeToMenu() {
            hooks.add("menu");
          }

          @Override
          public void cgameCatcherCleared() {
            hooks.add("cgame");
          }
        });
    input.setCatchers(ClientInput.KEYCATCH_CGAME);
    input.keyEvent(KeyNames.ESCAPE, true, 1000);
    input.keyEvent(KeyNames.ESCAPE, false, 1000);
    input.keyEvent(KeyNames.ESCAPE, true, 1001);
    assertEquals(List.of("cgame", "menu"), hooks);
    assertEquals(0, input.catchers());
  }

  @Test
  void clearingCatchersReleasesHeldButtons() {
    key('w', true, 1000);
    input.setCatchers(ClientInput.KEYCATCH_UI);
    input.setCatchers(0);
    console.frame();
    input.createCommand(1008, 1008, 0, 1, 0);
    assertEquals(0, input.createCommand(1016, 1016, 0, 1, 0).forward());
    assertTrue(input.isDown('w') == false);
  }
}
