package dev.bluevista.craftq3.engine.client.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.ClientHostServices;
import dev.bluevista.craftq3.engine.client.console.ClientConsole;
import dev.bluevista.craftq3.engine.client.input.ClientInput;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.console.ConfigSink;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.console.KeyNames;
import dev.bluevista.craftq3.engine.fs.HomeStore;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.vmabi.service.TimeServices;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HostServices} owns the console commands it registers (a rebuild binds its own sound
 * system), keeps the console's chat routing and reads the CD key from the original locations
 * without printing it.
 */
final class HostServicesTest {
  private static final String INSTALL_KEY = "abcdefgh12345678";
  private static final String HOME_KEY = "homekey987654321";

  @TempDir Path install;
  @TempDir Path homes;

  private final List<String> printed = new ArrayList<>();
  private final ClientConsole output = new ClientConsole();
  private Console console;

  private Console console() {
    ThreadOwner owner = new ThreadOwner("test client");
    owner.claim();
    output.mirror(printed::add);
    return Console.client(owner, output, path -> Optional.empty(), ConfigSink.NONE);
  }

  private HostServices services(SearchPath path, HomeStore home) {
    return new HostServices(
        console,
        output,
        path.view(),
        home,
        install,
        ClientHostServices.headless(frame -> {}, 640, 480),
        new TimeServices(new ManualClock(0), java.time.Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
        false);
  }

  private SearchPath mount(String game) throws Exception {
    Files.createDirectories(install.resolve("baseq3"));
    Files.createDirectories(install.resolve(game));
    return SearchPath.mount(install, game);
  }

  @Test
  void aRebuildReplacesTheSoundCommandsAndCloseRemovesThem() throws Exception {
    console = console();
    try (SearchPath path = mount("baseq3")) {
      HostServices first = services(path, null);
      assertEquals(List.of("play", "music", "stopsound"), first.ownCommands());
      first.close();
      for (String name : List.of("play", "music", "stopsound"))
        assertFalse(console.commands().exists(name), name);
      HostServices second = services(path, null);
      assertEquals(List.of("play", "music", "stopsound"), second.ownCommands());
      assertFalse(
          printed.stream().anyMatch(line -> line.contains("already defined")), "" + printed);
      second.close();
    }
  }

  @Test
  void aCommandSomeoneElseOwnsIsNeitherReplacedNorRemoved() throws Exception {
    console = console();
    List<String> ran = new ArrayList<>();
    console.commands().register("stopsound", args -> ran.add("theirs"));
    try (SearchPath path = mount("baseq3")) {
      HostServices services = services(path, null);
      assertEquals(List.of("play", "music"), services.ownCommands());
      services.close();
      assertTrue(console.commands().exists("stopsound"));
      console.commands().execute("stopsound");
      assertEquals(List.of("theirs"), ran);
    }
  }

  @Test
  void theOwnersChatPredicateSurvivesARebuild() throws Exception {
    console = console();
    output.attach(console.buffer(), () -> true);
    try (SearchPath path = mount("baseq3")) {
      services(path, null).close();
      HostServices services = services(path, null);
      // Connected: plain console text is chat.
      for (char c : "hello".toCharArray()) output.keyEvent(c | ClientInput.K_CHAR_FLAG, true, 0);
      output.keyEvent(KeyNames.ENTER, true, 0);
      assertEquals("cmd say hello\n", console.buffer().pendingText(), "chat routed to the server");
      services.close();
    }
  }

  @Test
  void theCdKeyComesFromTheGameHomeThenTheInstallsBaseq3() throws Exception {
    console = console();
    Files.createDirectories(install.resolve("baseq3"));
    Files.writeString(
        install.resolve("baseq3/q3key"), INSTALL_KEY + "\n", StandardCharsets.ISO_8859_1);
    try (SearchPath path = mount("cpma");
        HomeStore home = HomeStore.open(homes.resolve("cpma"))) {
      HostServices services = services(path, home);
      assertEquals(INSTALL_KEY, services.cdKey(), "the original location");
      services.close();
      Files.writeString(home.root().resolve("q3key"), HOME_KEY, StandardCharsets.ISO_8859_1);
      services = services(path, home);
      assertEquals(HOME_KEY, services.cdKey(), "the per-game home wins");
      services.close();
    }
    for (String line : printed) {
      assertFalse(line.contains(INSTALL_KEY), line);
      assertFalse(line.contains(HOME_KEY), line);
    }
  }
}
