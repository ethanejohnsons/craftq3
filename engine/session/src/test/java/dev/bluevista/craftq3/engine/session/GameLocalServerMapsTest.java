package dev.bluevista.craftq3.engine.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.console.ConsoleOutput;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.game.map.GameMap;
import dev.bluevista.craftq3.engine.game.map.MapSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link GameLocalServer#factory}: the platform's decorator sees every map the server loads. */
class GameLocalServerMapsTest {
  @TempDir Path root;

  @Test
  void decoratorWrapsTheServersOwnMaps() throws Exception {
    Path install = root.resolve("install");
    SyntheticRig.writeInstall(install);
    List<String> asked = new ArrayList<>();
    MapSource[] base = new MapSource[1];
    LocalServer.Factory factory =
        GameLocalServer.factory(
            files -> {
              base[0] = files;
              return new MapSource() {
                @Override
                public boolean exists(String name) {
                  asked.add("exists " + name);
                  return files.exists(name);
                }

                @Override
                public GameMap load(String name) throws IOException {
                  asked.add("load " + name);
                  return files.load(name);
                }
              };
            });
    ThreadOwner owner = new ThreadOwner("test server");
    try (SearchPath search = SearchPath.mount(install, SearchPath.BASE_GAME)) {
      LocalServer server =
          factory.create(
              new LocalServer.Context(
                  search,
                  null,
                  install,
                  SearchPath.BASE_GAME,
                  owner,
                  new ManualClock(0),
                  java.time.Clock.systemUTC(),
                  1,
                  ConsoleOutput.DISCARD,
                  false));
      owner.claim();
      try {
        assertNotNull(base[0], "the decorator received the default source");
        assertTrue(base[0].exists("test"), "the default source reads the game's maps");
        GameLocalServer local = (GameLocalServer) server;
        // The placeholder BSP cannot spawn, but the server asked the decorated source for it.
        assertFalse(local.server().spawn("test", true));
        assertEquals("load test", asked.getLast(), asked.toString());
      } finally {
        server.close();
        owner.release();
      }
    }
  }
}
