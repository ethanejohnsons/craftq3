package dev.bluevista.craftq3.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.video.Cinematics;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Plays every RoQ of baseq3 through {@link Cinematics} to its end, with frames and audio. */
@RealData
final class RealCinematicsTest {
  @TestFactory
  Stream<DynamicTest> everyRoqPlaysToItsEnd() throws Exception {
    var install = RealDataRoot.require();
    java.util.List<String> names;
    try (SearchPath path = SearchPath.mount(install, "baseq3")) {
      names =
          path.view().list("video", "roq").stream()
              .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".roq"))
              .toList();
    }
    assertFalse(names.isEmpty());
    return names.stream()
        .map(
            name ->
                DynamicTest.dynamicTest(
                    name,
                    () -> {
                      try (SearchPath path = SearchPath.mount(install, "baseq3");
                          ClientRig rig = new ClientRig(path.view(), install, true)) {
                        Cinematics cinematics =
                            new Cinematics(
                                p -> path.view().open(VirtualPath.of(p)),
                                rig.services.sound(),
                                rig.printed::add);
                        int handle = cinematics.play(name, 0, 0, 640, 480, 0, 0);
                        assertTrue(handle >= 0, String.join("\n", rig.printed));
                        int drawn = 0, status = Cinematics.FMV_PLAY;
                        for (int t = 0; t < 30 * 60_000 && status == Cinematics.FMV_PLAY; t += 33) {
                          status = cinematics.run(handle, t);
                          if (cinematics.draw(handle, 640, 480).isPresent()) drawn++;
                          rig.services.updateSound(33);
                        }
                        assertEquals(Cinematics.FMV_EOF, status, String.join("\n", rig.printed));
                        assertTrue(drawn > 0);
                        System.out.println(name + ": " + drawn + " frames drawn");
                        cinematics.close();
                      }
                    }));
  }
}
