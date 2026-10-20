package dev.bluevista.craftq3.engine.vm;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parses every distinct QVM build in the user's installation ({@link RealQvmBuilds}) and runs it up
 * to its first syscall. Nothing read here is committed or printed beyond file names and pass/fail
 * reasons.
 */
@RealData
final class RealQvmCorpusTest {
  /** vmMain commands tried on a fresh VM: 0 is *_INIT or UI_GETAPIVERSION; 1 and 2 are not. */
  private static final int[] COMMANDS = {0, 1, 2, 9999};

  private static final class FirstSyscall extends RuntimeException {
    private static final long serialVersionUID = 1L;

    FirstSyscall(int number) {
      super("first syscall " + number, null, false, false);
    }
  }

  @Test
  void everyDistinctQvmParsesValidatesAndRunsToItsFirstSyscall() throws Exception {
    RealQvmBuilds corpus = RealQvmBuilds.scan(RealDataRoot.require());
    List<String> rejected = new ArrayList<>();
    int ran = 0, reachedSyscall = 0, returned = 0;
    long executed = 0;
    for (RealQvmBuilds.Build build : corpus.builds.values()) {
      QvmImage image;
      try {
        image = QvmImage.parse(build.source(), build.bytes());
      } catch (QvmFormatException failure) {
        rejected.add(failure.getMessage());
        continue;
      }
      var vm =
          new QvmInstance(
              image,
              (self, number, args) -> {
                throw new FirstSyscall(number);
              });
      for (int command : COMMANDS) {
        vm.reset();
        try {
          vm.vmMain(command);
          returned++;
        } catch (QvmException failure) {
          if (failure.getCause() instanceof FirstSyscall) reachedSyscall++;
          else rejected.add("vmMain(" + command + ") " + failure.getMessage());
        }
        executed += vm.stats().instructions();
      }
      ran++;
    }

    System.out.printf(
        "QVM corpus: %d files, %d distinct builds, %d ran (%d calls reached a syscall, %d returned,"
            + " %d instructions), %d skipped archives%n",
        corpus.occurrences,
        corpus.builds.size(),
        ran,
        reachedSyscall,
        returned,
        executed,
        corpus.skippedArchives.size());
    corpus.skippedArchives.forEach(name -> System.out.println("  skipped " + name));
    rejected.forEach(reason -> System.out.println("  REJECTED " + reason));
    assertFalse(corpus.builds.isEmpty(), "no QVMs found");
    assertEquals(List.of(), rejected);
  }
}
