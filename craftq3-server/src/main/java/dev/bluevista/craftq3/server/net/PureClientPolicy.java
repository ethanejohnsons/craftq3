package dev.bluevista.craftq3.server.net;

import dev.bluevista.craftq3.core.command.CommandParser;
import java.util.HashSet;
import java.util.Set;

/** Pure admission policy, separated from packet parsing and original game callbacks. */
public final class PureClientPolicy {
  public enum Result {
    IGNORED,
    ACCEPTED,
    REJECTED
  }

  private PureClientPolicy() {}

  /** Old-level responses preserve existing admission state; only a current rejection drops it. */
  public static Result verify(
      CommandParser.Command command,
      boolean pure,
      int minimumServerId,
      int checksumFeed,
      int cgameChecksum,
      int uiChecksum,
      Set<Integer> allowed) {
    if (!pure) return Result.IGNORED;
    var args = command.arguments();
    try {
      int id = args.size() < 2 ? 0 : Integer.parseInt(args.get(1));
      if (id < minimumServerId) return Result.IGNORED;
      if (args.size() < 6
          || !args.get(4).equals("@")
          || Integer.parseInt(args.get(2)) != cgameChecksum
          || Integer.parseInt(args.get(3)) != uiChecksum) return Result.REJECTED;
      int combined = checksumFeed, count = 0;
      var seen = new HashSet<Integer>();
      for (int i = 5; i < args.size() - 1; i++) {
        int value = Integer.parseInt(args.get(i));
        if (!allowed.contains(value) || !seen.add(value)) return Result.REJECTED;
        combined ^= value;
        count++;
      }
      return (combined ^ count) == Integer.parseInt(args.getLast())
          ? Result.ACCEPTED
          : Result.REJECTED;
    } catch (NumberFormatException invalid) {
      return Result.REJECTED;
    }
  }
}
