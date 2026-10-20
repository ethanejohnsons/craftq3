package dev.bluevista.craftq3.engine.botlib.ea;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.util.Arrays;
import java.util.Objects;

/**
 * Per-client action collection for the original game VM. No movement, weapon, or bot AI rules.
 * Single-threaded like the rest of botlib; command callbacks run synchronously and may reenter.
 */
public final class ElementaryActions implements AutoCloseable {
  public static final int MAX_CLIENTS = 64;
  public static final int MAX_COMMAND_BYTES = 1023;
  private final InputState[] clients;
  private final CommandSink commands;
  private boolean closed;

  @FunctionalInterface
  public interface CommandSink {
    /** The host must handle this as a Quake client command, never an operating-system command. */
    void command(int client, String command);
  }

  public ElementaryActions(int maxClients, CommandSink commands) {
    if (maxClients < 1 || maxClients > MAX_CLIENTS)
      throw new IllegalArgumentException("Client count outside [1, 64]");
    clients = new InputState[maxClients];
    Arrays.setAll(clients, unused -> new InputState());
    this.commands = Objects.requireNonNull(commands);
  }

  public int maxClients() {
    return clients.length;
  }

  /** Raw EA_Action preserves all 32 bits, including extension bits and the history marker. */
  public void action(int client, int flags) {
    state(client).flags |= flags;
  }

  public void attack(int client) {
    action(client, ActionFlags.ATTACK);
  }

  public void use(int client) {
    action(client, ActionFlags.USE);
  }

  public void respawn(int client) {
    action(client, ActionFlags.RESPAWN);
  }

  public void crouch(int client) {
    action(client, ActionFlags.CROUCH);
  }

  public void walk(int client) {
    action(client, ActionFlags.WALK);
  }

  public void moveUp(int client) {
    action(client, ActionFlags.MOVE_UP);
  }

  public void moveDown(int client) {
    action(client, ActionFlags.MOVE_DOWN);
  }

  public void moveForward(int client) {
    action(client, ActionFlags.MOVE_FORWARD);
  }

  public void moveBack(int client) {
    action(client, ActionFlags.MOVE_BACK);
  }

  public void moveLeft(int client) {
    action(client, ActionFlags.MOVE_LEFT);
  }

  public void moveRight(int client) {
    action(client, ActionFlags.MOVE_RIGHT);
  }

  public void talk(int client) {
    action(client, ActionFlags.TALK);
  }

  public void gesture(int client) {
    action(client, ActionFlags.GESTURE);
  }

  public void jump(int client) {
    jumpAction(state(client), ActionFlags.JUMP);
  }

  public void delayedJump(int client) {
    jumpAction(state(client), ActionFlags.DELAYED_JUMP);
  }

  private static void jumpAction(InputState input, int flag) {
    if ((input.flags & ActionFlags.JUMPED_LAST_FRAME) != 0) input.flags &= ~flag;
    else input.flags |= flag;
  }

  public void move(int client, Vec3f direction, float speed) {
    InputState input = state(client);
    Vec3f checked = BotInput.vector(direction, BotInput.MAX_DIRECTION_COMPONENT);
    if (!Float.isFinite(speed)) throw new IllegalArgumentException("Non-finite bot speed");
    input.direction = checked;
    input.speed = Math.clamp(speed, -400, 400);
  }

  public void view(int client, Vec3f angles) {
    state(client).viewAngles = BotInput.vector(angles, BotInput.MAX_VIEW_COMPONENT);
  }

  /** Weapon IDs are opaque VM integers. Selection is not validated against Java game rules. */
  public void selectWeapon(int client, int weapon) {
    state(client).weapon = weapon;
  }

  public BotInput getInput(int client, float thinkTime) {
    InputState input = state(client);
    BotInput.checkThinkTime(thinkTime);
    input.thinkTime = thinkTime;
    return input.snapshot();
  }

  /** Host inspection without changing the last EA_GetInput think time. */
  public BotInput snapshot(int client) {
    return state(client).snapshot();
  }

  /** EA_EndRegular has no externally observable input or command effect in the native oracle. */
  public void endRegular(int client, float thinkTime) {
    state(client);
    BotInput.checkThinkTime(thinkTime);
  }

  public void resetInput(int client) {
    InputState input = state(client);
    input.flags = (input.flags & ActionFlags.JUMP) != 0 ? ActionFlags.JUMPED_LAST_FRAME : 0;
    input.direction = Vec3f.ZERO;
    input.speed = 0;
    input.thinkTime = 0;
  }

  /** Host lifecycle operation for a disconnected/reused slot; also clears held view and weapon. */
  public void clearClient(int client) {
    state(client);
    clients[client] = new InputState();
  }

  public void say(int client, String text) {
    command(client, "say " + Objects.requireNonNull(text));
  }

  public void sayTeam(int client, String text) {
    command(client, "say_team " + Objects.requireNonNull(text));
  }

  public void command(int client, String command) {
    state(client);
    Objects.requireNonNull(command);
    if (command.length() > MAX_COMMAND_BYTES)
      throw new IllegalArgumentException("Quake client command exceeds 1023 bytes");
    for (int i = 0; i < command.length(); i++) {
      char value = command.charAt(i);
      if (value == 0 || value > 255)
        throw new IllegalArgumentException("Quake client commands require non-NUL Latin-1 text");
    }
    commands.command(client, command);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    Arrays.fill(clients, null);
  }

  private InputState state(int client) {
    if (closed) throw new IllegalStateException("Elementary action service is closed");
    if (client < 0 || client >= clients.length)
      throw new IllegalArgumentException("Invalid bot client " + client);
    return clients[client];
  }

  private static final class InputState {
    private float thinkTime, speed;
    private Vec3f direction = Vec3f.ZERO, viewAngles = Vec3f.ZERO;
    private int flags, weapon;

    BotInput snapshot() {
      return new BotInput(thinkTime, direction, speed, viewAngles, flags, weapon);
    }
  }
}
