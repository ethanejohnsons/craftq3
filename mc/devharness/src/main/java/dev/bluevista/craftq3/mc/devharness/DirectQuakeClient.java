package dev.bluevista.craftq3.mc.devharness;

import dev.bluevista.craftq3.engine.game.GameServer;
import dev.bluevista.craftq3.engine.game.ServerClient;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.List;

/**
 * A Quake client for the server GameTests that skips the network: it is admitted on the game server
 * under a bridged player's address (so the bridge binds it to that player exactly as a payload
 * connection would) and sends one user command per tick straight into {@code GameServer.userMove}.
 * The client game tests cover the real payload connection.
 */
final class DirectQuakeClient {
  /** {@code BUTTON_ATTACK}. */
  static final int ATTACK = 1;

  private final GameServer server;
  private final int slot;
  int forward;
  int right;
  int up;
  int buttons;
  int weapon;

  DirectQuakeClient(GameServer server, String address, String name) {
    this.server = server;
    GameServer.Admission admission =
        server.connect(
            "\\name\\" + name + "\\model\\sarge\\headmodel\\sarge\\snaps\\20\\rate\\25000",
            address,
            false,
            -1);
    if (!admission.accepted()) throw new AssertionError("not admitted: " + admission.denied());
    slot = admission.client();
    server.gamestateSent(slot);
  }

  int slot() {
    return slot;
  }

  boolean active() {
    return server.client(slot).state() == ServerClient.State.ACTIVE;
  }

  /** Sends this tick's command (call once per server tick). */
  void send() {
    if (server.state() != GameServer.State.RUNNING) return;
    server.packetReceived(slot);
    // Nobody reads this client's reliable commands; acknowledge them as a real client would.
    ServerClient client = server.client(slot);
    if (client.state() != ServerClient.State.FREE)
      client.acknowledgeReliable(client.reliableSequence());
    int time = Math.max(1, server.serverTime());
    server.userMove(slot, List.of(new UserCmd(time, 0, 0, 0, buttons, weapon, forward, right, up)));
  }

  int stat(int index) {
    return server.entities().orElseThrow().playerInt(slot, PlayerStateLayout.STATS + index * 4);
  }

  int ammo(int weaponNumber) {
    return server
        .entities()
        .orElseThrow()
        .playerInt(slot, PlayerStateLayout.AMMO + weaponNumber * 4);
  }

  void drop() {
    if (server.state() == GameServer.State.RUNNING
        && server.client(slot).state() != ServerClient.State.FREE) server.drop(slot, "test over");
  }
}
