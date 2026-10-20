package dev.bluevista.craftq3.engine.formats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.log.Log;
import dev.bluevista.craftq3.engine.formats.aas.AasMap;
import dev.bluevista.craftq3.engine.formats.aas.AasReader;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.bsp.SurfaceType;
import dev.bluevista.craftq3.engine.formats.image.ImageLoader;
import dev.bluevista.craftq3.engine.formats.md3.AnimationConfig;
import dev.bluevista.craftq3.engine.formats.md3.Md3Reader;
import dev.bluevista.craftq3.engine.formats.md3.SkinParser;
import dev.bluevista.craftq3.engine.formats.roq.RoqDecoder;
import dev.bluevista.craftq3.engine.formats.shader.ShaderDefinition;
import dev.bluevista.craftq3.engine.formats.shader.ShaderLibrary;
import dev.bluevista.craftq3.engine.formats.shader.ShaderParser;
import dev.bluevista.craftq3.engine.formats.wav.WavReader;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Parses every supported entry of every PK3 in the user's installation, then resolves the shaders
 * and images referenced by every map's faces. Writes {@code build/realdata/formats.txt}.
 */
@RealData
class RealDataSweepTest {
  /**
   * Entries of the reference installation that the original engine refuses too, by path inside the
   * install: Mac resource-fork stubs saved with a .wav name (509 bytes, no RIFF header), an MP3
   * stream in a WAV container (format tag 0x55; the original loader accepts PCM only), and Q3Rally
   * vehicle configs named animation.cfg (a "plate" directive, no animation rows; only Q3Rally's own
   * cgame reads them).
   */
  private static final Set<String> KNOWN_REJECTED =
      Set.of(
          "baseq3/gon2_nomusic.pk3:sound/gon2/RESOURCE.FRK/BOOM.WAV",
          "baseq3/gon2_nomusic.pk3:sound/gon2/RESOURCE.FRK/DOOR.WAV",
          "baseq3/gon2_nomusic.pk3:sound/gon2/RESOURCE.FRK/JUMP.WAV",
          "baseq3/map_zastavka_v13.pk3:sound/world/kohout.wav",
          "q3rally/q3r_v132.pk3:models/players/alpine/animation.cfg",
          "q3rally/q3r_v132.pk3:models/players/murcielago/animation.cfg",
          "q3rally/q3r_v132.pk3:models/players/raptor/animation.cfg",
          "q3rally/q3r_v132.pk3:models/players/reaper/animation.cfg",
          "q3rally/q3r_v132.pk3:models/players/sidepipe/animation.cfg",
          "q3rally/q3r_v132.pk3:models/players/speed12/animation.cfg",
          "q3rally/q3r_v132.pk3:models/players/viper/animation.cfg");

  enum Kind {
    BSP,
    AAS,
    MD3,
    SHADER,
    SKIN,
    ANIMATION,
    WAV,
    ROQ,
    TGA,
    JPG
  }

  private static final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
  private static Log.Sink previousSink;
  private static Path root;
  private static final List<Path> skipped = new ArrayList<>();
  private static final Map<Path, ZipFile> archives = new TreeMap<>();

  @BeforeAll
  static void open() throws IOException {
    previousSink =
        Log.install(
            (name, level, message, error) -> {
              if (level.compareTo(Log.Level.WARN) >= 0 && warnings.size() < 5000)
                warnings.add(message);
            });
    root = RealDataRoot.require();
    List<Path> pk3s;
    try (Stream<Path> walk = Files.walk(root)) {
      pk3s =
          walk.filter(p -> QPath.extension(p.getFileName().toString()).equals("pk3"))
              .filter(Files::isRegularFile)
              .sorted()
              .toList();
    }
    for (Path pk3 : pk3s) {
      try {
        if (Files.size(pk3) == 0) throw new IOException("zero-byte file");
        // Q3 stores names as raw bytes; Latin-1 maps every byte, so no archive is lost to UTF-8.
        archives.put(pk3, new ZipFile(pk3.toFile(), StandardCharsets.ISO_8859_1));
      } catch (IOException e) {
        skipped.add(pk3);
      }
    }
  }

  @AfterAll
  static void close() throws IOException {
    for (ZipFile zip : archives.values()) zip.close();
    if (previousSink != null) Log.install(previousSink);
  }

  private record Failure(Kind kind, String path, String reason) {}

  @Test
  void parsesEveryEntryAndResolvesEveryMapTexture() throws Exception {
    Map<Kind, AtomicInteger> counts = new EnumMap<>(Kind.class);
    for (Kind kind : Kind.values()) counts.put(kind, new AtomicInteger());
    ConcurrentLinkedQueue<Failure> failures = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<String> notes = new ConcurrentLinkedQueue<>();
    Map<String, Integer> bspChecksums = new ConcurrentHashMap<>();
    Map<String, Integer> aasChecksums = new ConcurrentHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Future<?>> work = new ArrayList<>();
      for (Map.Entry<Path, ZipFile> archive : archives.entrySet())
        work.add(
            pool.submit(
                () -> {
                  sweep(
                      archive.getKey(),
                      archive.getValue(),
                      counts,
                      failures,
                      notes,
                      bspChecksums,
                      aasChecksums);
                  return null;
                }));
      for (Future<?> future : work) future.get();
    }
    Map<String, ResolutionReport> resolution = resolveMaps();
    List<Failure> unexpected =
        failures.stream().filter(f -> !KNOWN_REJECTED.contains(f.path())).toList();
    writeReport(counts, failures, notes, resolution, bspChecksums, aasChecksums);
    assertEquals(15, skipped.size(), "unreadable archives: " + skipped);
    assertTrue(unexpected.isEmpty(), "parse failures:\n" + describe(unexpected));
    List<String> baseUnresolved = new ArrayList<>();
    resolution.forEach(
        (map, report) -> {
          if (report.retail() && !report.unresolved().isEmpty())
            baseUnresolved.add(map + " " + report.unresolved());
        });
    assertTrue(baseUnresolved.isEmpty(), "retail maps with unresolved textures: " + baseUnresolved);
  }

  // ---- parsing ------------------------------------------------------------------------------

  private static void sweep(
      Path pk3,
      ZipFile zip,
      Map<Kind, AtomicInteger> counts,
      ConcurrentLinkedQueue<Failure> failures,
      ConcurrentLinkedQueue<String> notes,
      Map<String, Integer> bspChecksums,
      Map<String, Integer> aasChecksums) {
    String archive = root.relativize(pk3).toString();
    for (ZipEntry entry : Collections.list(zip.entries())) {
      if (entry.isDirectory()) continue;
      String name = QPath.normalize(entry.getName());
      Kind kind = kindOf(name);
      if (kind == null) continue;
      String path = archive + ":" + entry.getName();
      counts.get(kind).incrementAndGet();
      try {
        if (kind == Kind.ROQ) {
          try (InputStream input = zip.getInputStream(entry)) {
            roq(input);
          }
          continue;
        }
        byte[] data;
        try (InputStream input = zip.getInputStream(entry)) {
          data = input.readAllBytes();
        }
        String mapKey = pk3.getParent() + "|" + QPath.stripExtension(name);
        switch (kind) {
          case BSP -> {
            BspMap map = BspReader.read(data, path);
            bspChecksums.put(mapKey, map.checksum());
            if (!map.diagnostics().isEmpty()) notes.add(path + ": " + map.diagnostics());
          }
          case AAS -> {
            AasMap aas = AasReader.read(data, path);
            aasChecksums.put(mapKey, aas.bspChecksum());
            if (!aas.diagnostics().isEmpty()) notes.add(path + ": " + aas.diagnostics());
          }
          case MD3 -> Md3Reader.read(data, path);
          case SHADER -> {
            var result = ShaderParser.parse(path, latin1(data));
            result.diagnostics().stream()
                .filter(d -> d.message().startsWith("malformed shader"))
                .forEach(d -> notes.add(path + ":" + d.line() + ": " + d.message()));
          }
          case SKIN -> SkinParser.parse(latin1(data));
          case ANIMATION -> AnimationConfig.parse(latin1(data));
          case WAV -> WavReader.read(data);
          case TGA -> ImageLoader.decodeTga(data);
          case JPG -> ImageLoader.decodeJpeg(data);
          case ROQ -> throw new AssertionError();
        }
      } catch (FormatException e) {
        failures.add(new Failure(kind, path, e.getMessage()));
      } catch (IOException | RuntimeException e) {
        failures.add(new Failure(kind, path, "UNEXPECTED " + e));
      }
    }
  }

  private static void roq(InputStream input) throws IOException {
    try (RoqDecoder decoder = new RoqDecoder(input)) {
      while (decoder.next().isPresent()) {
        // Decode every frame and audio chunk.
      }
    }
  }

  private static Kind kindOf(String name) {
    if (name.endsWith("/animation.cfg") || name.equals("animation.cfg")) return Kind.ANIMATION;
    return switch (QPath.extension(name)) {
      case "bsp" -> Kind.BSP;
      case "aas" -> Kind.AAS;
      case "md3" -> Kind.MD3;
      case "shader" -> Kind.SHADER;
      case "skin" -> Kind.SKIN;
      case "wav" -> Kind.WAV;
      case "roq" -> Kind.ROQ;
      case "tga" -> Kind.TGA;
      case "jpg", "jpeg" -> Kind.JPG;
      default -> null;
    };
  }

  private static String latin1(byte[] data) {
    return new String(data, StandardCharsets.ISO_8859_1);
  }

  // ---- resolution ---------------------------------------------------------------------------

  /**
   * Unresolved names of one map: stage and implicit images, and (reported separately) sky-box
   * sides, which the renderer replaces with its default image while the cloud stages still draw.
   */
  private record ResolutionReport(
      boolean retail, int textures, Set<String> unresolved, Set<String> missingSkyBoxes) {}

  /** Mount groups: each directory holding PK3s, falling back to baseq3. */
  private static Map<String, ResolutionReport> resolveMaps() throws IOException {
    Path baseq3 = root.resolve("baseq3");
    Map<Path, List<Path>> groups = new TreeMap<>();
    for (Path pk3 : archives.keySet())
      groups.computeIfAbsent(pk3.getParent(), k -> new ArrayList<>()).add(pk3);
    List<Pk3SearchPath.Source> baseSources =
        sources(baseq3, groups.getOrDefault(baseq3, List.of()));
    Map<String, ResolutionReport> reports = new TreeMap<>();
    for (Map.Entry<Path, List<Path>> group : groups.entrySet()) {
      List<Pk3SearchPath.Source> mounted = new ArrayList<>();
      if (!group.getKey().equals(baseq3)) mounted.addAll(sources(group.getKey(), group.getValue()));
      mounted.addAll(baseSources);
      Pk3SearchPath search = new Pk3SearchPath(mounted);
      ShaderLibrary shaders = ShaderLibrary.load(search);
      for (Path pk3 : group.getValue()) {
        ZipFile zip = archives.get(pk3);
        for (ZipEntry entry : Collections.list(zip.entries())) {
          String name = QPath.normalize(entry.getName());
          if (entry.isDirectory() || !QPath.extension(name).equals("bsp")) continue;
          BspMap map;
          try (InputStream input = zip.getInputStream(entry)) {
            map = BspReader.read(input.readAllBytes());
          } catch (FormatException e) {
            continue; // reported by the parse sweep
          }
          boolean retail =
              pk3.getFileName().toString().equalsIgnoreCase("pak0.pk3")
                  && pk3.getParent().equals(baseq3)
                  && name.matches("maps/(q3dm|q3tourney|q3ctf).*");
          reports.put(root.relativize(pk3) + ":" + name, resolve(map, shaders, search, retail));
        }
      }
    }
    return reports;
  }

  /** A game directory's PK3s (later names override earlier ones) above its loose files. */
  private static List<Pk3SearchPath.Source> sources(Path directory, List<Path> pk3s)
      throws IOException {
    List<Path> ordered = new ArrayList<>(pk3s);
    ordered.sort(
        Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT))
            .reversed());
    List<Pk3SearchPath.Source> sources = new ArrayList<>();
    for (Path pk3 : ordered) sources.add(Pk3SearchPath.Archive.of(archives.get(pk3)));
    sources.add(Pk3SearchPath.Directory.of(directory));
    return sources;
  }

  private static ResolutionReport resolve(
      BspMap map, ShaderLibrary shaders, AssetLookup search, boolean retail) {
    Set<String> referenced = new TreeSet<>();
    for (int face = 0; face < map.faces().count(); face++) {
      if (map.faces().surfaceType(face) == SurfaceType.BAD) continue;
      referenced.add(map.textures().get(map.faces().texture(face)).name());
    }
    Set<String> unresolved = new TreeSet<>();
    Set<String> skyBoxes = new TreeSet<>();
    for (String texture : referenced) {
      var definition = shaders.find(texture);
      if (definition.isEmpty()) {
        if (ImageLoader.find(search, texture).isEmpty()) unresolved.add(texture);
        continue;
      }
      ShaderDefinition shader = definition.get();
      for (ShaderDefinition.Stage stage : shader.stages())
        for (String frame : stage.texture().frames())
          if (!frame.startsWith("$") && ImageLoader.find(search, frame).isEmpty())
            unresolved.add(texture + " -> " + frame);
      shader
          .sky()
          .ifPresent(
              sky -> {
                for (String box : new String[] {sky.farBox(), sky.nearBox()}) {
                  if (box.equals("-")) continue;
                  for (String side : new String[] {"rt", "bk", "lf", "ft", "up", "dn"})
                    if (ImageLoader.find(search, box + "_" + side + ".tga").isEmpty())
                      skyBoxes.add(texture + " -> " + box + "_" + side);
                }
              });
    }
    return new ResolutionReport(retail, referenced.size(), unresolved, skyBoxes);
  }

  // ---- report -------------------------------------------------------------------------------

  private static void writeReport(
      Map<Kind, AtomicInteger> counts,
      ConcurrentLinkedQueue<Failure> failures,
      ConcurrentLinkedQueue<String> notes,
      Map<String, ResolutionReport> resolution,
      Map<String, Integer> bspChecksums,
      Map<String, Integer> aasChecksums)
      throws IOException {
    StringBuilder out = new StringBuilder();
    out.append("Archives opened: ")
        .append(archives.size())
        .append(", skipped: ")
        .append(skipped.size())
        .append('\n');
    for (Path p : skipped) out.append("  skipped ").append(root.relativize(p)).append('\n');
    Map<Kind, List<Failure>> byKind = new EnumMap<>(Kind.class);
    for (Failure f : failures) byKind.computeIfAbsent(f.kind(), k -> new ArrayList<>()).add(f);
    out.append("\nEntries by type (parsed / failed):\n");
    for (Kind kind : Kind.values())
      out.append(
          String.format(
              "  %-9s %6d / %d%n",
              kind, counts.get(kind).get(), byKind.getOrDefault(kind, List.of()).size()));
    out.append("\nFailures:\n").append(describe(new ArrayList<>(failures)));
    int mismatched = 0;
    int checked = 0;
    for (Map.Entry<String, Integer> aas : aasChecksums.entrySet()) {
      Integer bsp = bspChecksums.get(aas.getKey());
      if (bsp == null) continue;
      checked++;
      if (!bsp.equals(aas.getValue())) {
        mismatched++;
        out.append("  AAS checksum mismatch: ").append(aas.getKey()).append('\n');
      }
    }
    out.append("\nAAS/BSP checksum pairs checked: ")
        .append(checked)
        .append(", mismatched: ")
        .append(mismatched)
        .append('\n');
    out.append("\nLenient-read notes (").append(notes.size()).append("):\n");
    notes.stream().sorted().forEach(n -> out.append("  ").append(n).append('\n'));
    out.append("\nTexture resolution per map (referenced / unresolved):\n");
    int total = 0;
    int incomplete = 0;
    for (Map.Entry<String, ResolutionReport> e : resolution.entrySet()) {
      ResolutionReport r = e.getValue();
      total += r.unresolved().size();
      if (!r.unresolved().isEmpty()) incomplete++;
      out.append(
          String.format(
              "  %-70s %4d / %d%s%n",
              e.getKey(), r.textures(), r.unresolved().size(), r.retail() ? " [retail]" : ""));
      for (String name : r.unresolved()) out.append("      ").append(name).append('\n');
      if (!r.missingSkyBoxes().isEmpty())
        out.append("      sky box sides missing: ").append(r.missingSkyBoxes()).append('\n');
    }
    out.append("\nMaps: ")
        .append(resolution.size())
        .append(", with unresolved textures: ")
        .append(incomplete)
        .append(", unresolved references: ")
        .append(total)
        .append('\n');
    out.append("\nLogged warnings (first ").append(Math.min(200, warnings.size())).append("):\n");
    synchronized (warnings) {
      warnings.stream().limit(200).forEach(w -> out.append("  ").append(w).append('\n'));
    }
    Path report = Path.of("build", "realdata", "formats.txt");
    Files.createDirectories(report.getParent());
    Files.writeString(report, out);
    System.out.println(out.substring(0, Math.min(out.length(), 4000)));
  }

  private static String describe(List<Failure> failures) {
    Map<Kind, List<Failure>> grouped = new LinkedHashMap<>();
    failures.stream()
        .sorted(Comparator.comparing(Failure::path))
        .forEach(f -> grouped.computeIfAbsent(f.kind(), k -> new ArrayList<>()).add(f));
    StringBuilder out = new StringBuilder();
    grouped.forEach(
        (kind, list) -> {
          out.append("  ").append(kind).append(" (").append(list.size()).append("):\n");
          for (Failure f : list)
            out.append("    ").append(f.path()).append(": ").append(f.reason()).append('\n');
        });
    return out.toString();
  }
}
