package dev.bluevista.craftq3.server.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.command.CommandParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PureClientPolicyTest {
  @Test
  void oldLevelResponsesAndNonPureServersLeaveAdmissionUntouched() {
    assertEquals(PureClientPolicy.Result.IGNORED, verify("cp 99 garbage", true, 0));
    assertEquals(PureClientPolicy.Result.IGNORED, verify("cp", true, 0));
    assertEquals(PureClientPolicy.Result.IGNORED, verify("cp garbage", false, 0));
  }

  @Test
  void currentAndLaterResponsesValidateModulesPackMembershipAndFeed() {
    for (int id : new int[] {100, 101, 150, Integer.MAX_VALUE})
      for (int feed : new int[] {0, 12345, -1, Integer.MIN_VALUE}) {
        assertEquals(
            PureClientPolicy.Result.ACCEPTED,
            verify("cp " + id + " 111 222 @ " + feed, true, feed));
        int sum = feed ^ 333 ^ 444 ^ 2;
        assertEquals(
            PureClientPolicy.Result.ACCEPTED,
            verify("cp " + id + " 111 222 @ 333 444 " + sum, true, feed));
        assertEquals(
            PureClientPolicy.Result.REJECTED,
            verify("cp " + id + " 111 222 @ 333 444 " + (sum ^ 1), true, feed));
      }
  }

  @Test
  void duplicatedForeignOrWrongModuleChecksumsAreRejected() {
    for (String command :
        new String[] {
          "cp 100 112 222 @ 0",
          "cp 100 111 223 @ 0",
          "cp 100 111 222 @ 333 333 2",
          "cp 100 111 222 @ 999 998",
          "cp 100 111 222 ! 0",
          "cp 100 111 222 @",
          "cp 100 111 222 @ bogus",
          "cp 100 111 222 @ 0 extra"
        }) assertEquals(PureClientPolicy.Result.REJECTED, verify(command, true, 0), command);
  }

  private static PureClientPolicy.Result verify(String text, boolean pure, int feed) {
    return PureClientPolicy.verify(
        CommandParser.tokenize(text), pure, 100, feed, 111, 222, Set.of(111, 222, 333, 444));
  }
}
