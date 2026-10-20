package dev.bluevista.craftq3.engine.fs;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Mounts every game directory of the user's installation. Expected values are derived from the
 * installation at run time (the JDK's ZIP reader is the independent oracle for archive validity);
 * nothing from the data is committed.
 */
@RealData
class RealInstallationTest {
  private static boolean isPk3(Path file) {
    return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pk3")
        && Files.isRegularFile(file);
  }

  private static List<Path> pk3s(Path directory) throws IOException {
    try (var children = Files.list(directory)) {
      return children.filter(RealInstallationTest::isPk3).sorted().toList();
    }
  }

  /** Game directories: baseq3 and every root subdirectory that directly holds a PK3. */
  private static List<Path> gameDirectories(Path root) throws IOException {
    var games = new ArrayList<Path>();
    try (var children = Files.list(root)) {
      for (Path child : children.filter(Files::isDirectory).sorted().toList())
        if (child.getFileName().toString().equalsIgnoreCase("baseq3") || !pk3s(child).isEmpty())
          games.add(child);
    }
    return games;
  }

  /** Archives the JDK reader also cannot open, read as raw ISO-8859-1 names. */
  private static long invalid(List<Path> archives) {
    long count = 0;
    for (Path archive : archives) {
      try (var zip = new ZipFile(archive.toFile(), StandardCharsets.ISO_8859_1)) {
        zip.size();
      } catch (IOException invalid) {
        count++;
      }
    }
    return count;
  }

  private static String source(SearchPath fs, String path) {
    return fs.find(VirtualPath.of(path))
        .orElseThrow(() -> new AssertionError(path + " does not resolve in " + fs.game()))
        .mount()
        .location()
        .getFileName()
        .toString();
  }

  @Test
  void everyGameDirectoryMountsAndSkipsOnlyInvalidArchives() throws Exception {
    Path root = RealDataRoot.require();
    List<Path> base = pk3s(root.resolve("baseq3"));
    long baseInvalid = invalid(base);
    var games = gameDirectories(root);
    assertTrue(games.size() > 1, "expected mods next to baseq3");
    for (Path game : games) {
      String name = game.getFileName().toString();
      boolean isBase = name.equalsIgnoreCase("baseq3");
      List<Path> own = pk3s(game);
      long expectedSkipped = invalid(own) + (isBase ? 0 : baseInvalid);
      long expectedMounted = own.size() + (isBase ? 0 : base.size()) - expectedSkipped;
      for (SearchOrder order : SearchOrder.values()) {
        try (var fs = SearchPath.mount(root, name, order)) {
          assertEquals(
              expectedSkipped,
              fs.report().skippedArchives().size(),
              name + " skipped " + fs.report().skippedArchives());
          assertEquals(expectedMounted, fs.packs().size(), name);
          if (order == SearchOrder.ORIGINAL_132)
            System.out.printf(
                "%s: %d packs, %d skipped archives, %d skipped entries%n",
                name,
                fs.packs().size(),
                fs.report().skippedArchives().size(),
                fs.report().skippedEntries());
        }
      }
    }
  }

  @Test
  void baseq3ResolvesMapsQvmsAndCorpusSpellings() throws Exception {
    Path root = RealDataRoot.require();
    try (var original = SearchPath.mount(root, null);
        var looseFirst = SearchPath.mount(root, null, SearchOrder.LOOSE_FIRST)) {
      assertTrue(original.find(VirtualPath.of("maps/q3dm17.bsp")).orElseThrow().inPack());
      assertTrue(original.read(VirtualPath.of("maps/q3dm17.bsp")).length > 0);
      assertEquals("pak8.pk3", source(original, "vm/qagame.qvm"));

      // The stray loose qagame.qvm sits at the directory root, not under vm/, so it is never the
      // game module; vm/qagame.qvm only changes source if a loose vm/qagame.qvm exists.
      boolean looseVm = Files.isRegularFile(root.resolve("baseq3/vm/qagame.qvm"));
      var loose = looseFirst.find(VirtualPath.of("vm/qagame.qvm")).orElseThrow();
      assertEquals(!looseVm, loose.inPack());
      if (!looseVm) assertEquals("pak8.pk3", source(looseFirst, "vm/qagame.qvm"));
      if (Files.isRegularFile(root.resolve("baseq3/qagame.qvm"))) {
        assertFalse(original.find(VirtualPath.of("qagame.qvm")).orElseThrow().inPack());
        assertFalse(looseFirst.find(VirtualPath.of("qagame.qvm")).orElseThrow().inPack());
      }

      // Every loose file that a baseq3 pack also has comes from the pack under the original
      // order and from the directory under loose-first.
      var directory =
          (DirectoryMount)
              original.mounts().stream()
                  .filter(m -> m instanceof DirectoryMount && m.game().equals("baseq3"))
                  .findFirst()
                  .orElseThrow();
      int overlaps = 0;
      for (String name : directory.names()) {
        VirtualPath path = VirtualPath.of(name);
        boolean packed =
            original.packs().stream().anyMatch(p -> p.archive().find(path).isPresent());
        if (!packed) continue;
        overlaps++;
        assertTrue(original.find(path).orElseThrow().inPack(), name);
        assertFalse(looseFirst.find(path).orElseThrow().inPack(), name);
      }
      assertTrue(overlaps > 0, "expected loose files that shadow or are shadowed by packs");

      // Backslash member names (Casdm9v1.pk3 stores env\sunset1_up.jpg).
      var casdm = original.packs().stream().filter(p -> p.name().equalsIgnoreCase("casdm9v1"));
      var pack = casdm.findFirst();
      if (pack.isPresent()) {
        var backslashed =
            pack.get().archive().entries().stream()
                .filter(e -> e.name().indexOf('\\') >= 0)
                .toList();
        assertFalse(backslashed.isEmpty());
        for (var entry : backslashed) {
          VirtualPath path = VirtualPath.of(entry.name().replace('\\', '/'));
          var found = original.find(path).orElseThrow();
          assertTrue(found.inPack(), entry.name());
          assertEquals(entry.size(), original.read(path).length, entry.name());
        }
        assertEquals(
            "Casdm9v1.pk3".toLowerCase(Locale.ROOT),
            source(original, "env/sunset1_up.jpg").toLowerCase(Locale.ROOT));
      }

      // A raw 0x87 byte in a member name without the UTF-8 flag (mario_opposing_castles.pk3).
      Path mario = root.resolve("baseq3/mario_opposing_castles.pk3");
      if (Files.isRegularFile(mario)) {
        var mounted =
            original.packs().stream()
                .filter(p -> p.name().equalsIgnoreCase("mario_opposing_castles"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mario_opposing_castles.pk3 not mounted"));
        long files;
        try (var zip = new ZipFile(mario.toFile(), StandardCharsets.ISO_8859_1)) {
          files = zip.stream().filter(e -> !e.isDirectory()).count();
        }
        assertEquals(files, mounted.archive().entries().size());
        var highByte =
            mounted.archive().entries().stream()
                .filter(e -> e.name().chars().anyMatch(c -> c >= 0x80))
                .findFirst()
                .orElseThrow();
        assertEquals(highByte.size(), original.read(highByte.path()).length);
      }
    }
  }

  @Test
  void q3pinballFallsBackToBaseq3ForItsMissingModules() throws Exception {
    Path root = RealDataRoot.require();
    if (!Files.isDirectory(root.resolve("q3pinball"))) return;
    try (var fs = SearchPath.mount(root, "q3pinball")) {
      var qagame = fs.find(VirtualPath.of("vm/qagame.qvm")).orElseThrow();
      assertEquals("q3pinball", qagame.mount().game());
      for (String module : List.of("vm/cgame.qvm", "vm/ui.qvm")) {
        var found = fs.find(VirtualPath.of(module)).orElseThrow();
        assertEquals("baseq3", found.mount().game(), module);
        assertEquals("pak8.pk3", source(fs, module));
      }
    }
  }
}
