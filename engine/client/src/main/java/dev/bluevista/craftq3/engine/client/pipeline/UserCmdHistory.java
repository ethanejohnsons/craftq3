package dev.bluevista.craftq3.engine.client.pipeline;

import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout.UserCmd;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The client's user commands ({@code cl.cmds}): a ring of {@value #CMD_BACKUP} by command number.
 * The cgame predicts from it ({@code trap_GetUserCmd}) and the connection sends its newest entries.
 */
public final class UserCmdHistory {
  /** {@code CMD_BACKUP}. */
  public static final int CMD_BACKUP = 64;

  private final UserCmd[] commands = new UserCmd[CMD_BACKUP];
  private int number;

  /** Appends a command and returns its number ({@code cl.cmdNumber}). */
  public int add(UserCmd command) {
    number++;
    commands[number & (CMD_BACKUP - 1)] = Objects.requireNonNull(command);
    return number;
  }

  /** The newest command number. */
  public int current() {
    return number;
  }

  /** Command {@code n}, or empty when it is older than the ring; future numbers are an error. */
  public Optional<UserCmd> get(int n) {
    if (n > number) throw new IllegalArgumentException("User command " + n + " >= " + number);
    if (n <= number - CMD_BACKUP) return Optional.empty();
    UserCmd command = commands[n & (CMD_BACKUP - 1)];
    return Optional.of(command == null ? UserCmd.idle(0) : command);
  }

  /** {@code map_restart}: every command reads as idle at time 0. */
  public void clearCommands() {
    Arrays.fill(commands, UserCmd.idle(0));
  }

  /** A new connection starts numbering from 0. */
  public void reset() {
    Arrays.fill(commands, null);
    number = 0;
  }
}
