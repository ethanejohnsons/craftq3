package dev.bluevista.craftq3.engine.headless;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.bluevista.craftq3.engine.fs.PackMount;
import dev.bluevista.craftq3.engine.session.CommandLine;
import dev.bluevista.craftq3.engine.session.SessionState;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PK3 downloads end to end: a HOSTING session (pure, {@code sv_allowDownload 1}) runs a synthetic
 * map from a custom PK3 only its installation has; a second session with {@code cl_allowDownload 1}
 * connects over UDP, downloads the pack (UDP download protocol), verifies and mounts it, and enters
 * the game. After a disconnect the pack is unmounted; reconnecting takes it from the download cache
 * without downloading again.
 *
 * <p>The map is made up here (a floor and a spawn point), so no game data is copied: both
 * installations link the retail {@code baseq3/pak0..8}; the host's has the synthetic pack in a mod
 * directory both play. (A mod's packs are always in {@code sv_referencedPaks}; a {@code baseq3}
 * custom map pack is not yet, because engine:game clears the pack references after reading the
 * map.)
 */
@RealData
final class RealDownloadTest {
  private static final String PACK = "zzcq3dl";
  private static final String MAP = "cq3dl";
  private static final String GAME = "cq3dltest";

  @TempDir Path hostInstall;
  @TempDir Path guestInstall;
  @TempDir Path hostHome;
  @TempDir Path guestHome;

  @Test
  void aMissingCustomPackIsDownloadedVerifiedAndMounted() throws Exception {
    Path real = RealDataRoot.require();
    install(real, hostInstall);
    install(real, guestInstall);
    byte[] pack = pack();
    Files.write(hostInstall.resolve(GAME).resolve(PACK + ".pk3"), pack);

    HeadlessClient.Options options =
        HeadlessClient.options(guestInstall)
            .withGame(GAME)
            .withManualClock(false)
            .withFrameMillis(10);
    try (HeadlessClient host =
            HeadlessClient.start(
                options
                    .withHome(hostHome)
                    .withNetEnabled(true)
                    .withCommandLine(
                        CommandLine.parse(
                            "+set vm_strictSyscalls 1 +set sv_pure 1 +set sv_allowDownload 1"
                                + " +set net_ip 127.0.0.1 +set net_port 0 +set name Hoster"))
                    .withInstall(hostInstall));
        HeadlessClient guest =
            HeadlessClient.start(
                options
                    .withSeed(43)
                    .withHome(guestHome)
                    .withCommandLine(
                        CommandLine.parse(
                            "+set vm_strictSyscalls 1 +set cl_allowDownload 1 +set name Guest")))) {
      assertFalse(mounted(guest), "the guest starts without the pack");
      host.command("map " + MAP);
      assertTrue(host.runUntil(SessionState.HOSTING, 120_000), host.tail(30));
      int port = host.session().hostedAddress().orElseThrow().getPort();

      guest.command("connect 127.0.0.1:" + port);
      assertTrue(play(host, guest, SessionState.REMOTE), guest.tail(40));
      assertTrue(mounted(guest), "the downloaded pack is mounted while connected");
      List<Path> cached = cachedPacks();
      assertEquals(1, cached.size(), "one pack downloaded: " + cached);
      assertArrayEquals(pack, Files.readAllBytes(cached.getFirst()), "the bytes the host serves");
      assertEquals("", guest.session().console().cvars().string("cl_downloadName"));
      assertEquals(0, guest.session().degradations(), guest.tail(30));

      guest.command("disconnect");
      guest.run(300);
      assertEquals(SessionState.MENU, guest.state());
      assertFalse(mounted(guest), "unmounted with the game");

      long downloadedAt = Files.getLastModifiedTime(cached.getFirst()).toMillis();
      int lines = guest.console().size();
      guest.command("connect 127.0.0.1:" + port);
      assertTrue(play(host, guest, SessionState.REMOTE), guest.tail(40));
      assertTrue(mounted(guest), "mounted again from the cache");
      assertEquals(List.of(cached.getFirst()), cachedPacks());
      assertEquals(downloadedAt, Files.getLastModifiedTime(cached.getFirst()).toMillis());
      assertTrue(
          guest.console().subList(lines, guest.console().size()).stream()
              .noneMatch(line -> line.contains("WARNING: You are missing")),
          guest.tail(30));
      assertEquals(SessionState.HOSTING, host.state(), host.tail(30));
      assertEquals(0, host.session().degradations(), host.tail(30));
    }
  }

  /** {@code baseq3} with links to the retail packs and an empty {@link #GAME} directory. */
  private static void install(Path real, Path into) throws IOException {
    Path base = Files.createDirectories(into.resolve("baseq3"));
    Files.createDirectories(into.resolve(GAME));
    try (Stream<Path> files = Files.list(real.resolve("baseq3"))) {
      for (Path pak :
          files.filter(f -> f.getFileName().toString().matches("(?i)pak[0-8]\\.pk3")).toList())
        link(base.resolve(pak.getFileName().toString()), pak);
    }
  }

  private static boolean play(HeadlessClient host, HeadlessClient guest, SessionState wanted) {
    long end = System.nanoTime() + 120_000_000_000L;
    while (System.nanoTime() < end && guest.state() != wanted) {
      host.frame();
      guest.frame();
    }
    return guest.state() == wanted;
  }

  private static boolean mounted(HeadlessClient client) {
    for (PackMount pack : client.session().searchPath().packs())
      if (pack.name().equalsIgnoreCase(PACK)) return true;
    return false;
  }

  private List<Path> cachedPacks() throws IOException {
    Path cache = guestHome.resolve(".downloads");
    if (!Files.isDirectory(cache)) return List.of();
    try (Stream<Path> files = Files.list(cache)) {
      return files.filter(f -> f.getFileName().toString().endsWith(".pk3")).sorted().toList();
    }
  }

  private static void link(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException noSymlinks) {
      try {
        Files.createLink(link, target);
      } catch (IOException | UnsupportedOperationException noLinks) {
        assumeTrue(false, "the file system supports neither symbolic nor hard links");
      }
    }
  }

  // ---------------------------------------------------------------- the synthetic pack

  private static byte[] pack() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("maps/" + MAP + ".bsp"));
      zip.write(SyntheticMap.bytes());
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("readme-craftq3.txt"));
      zip.write(
          "A synthetic map for the CraftQ3 download test.\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  /**
   * A made-up IBSP: two leaves split at x = 0 under one node, a floor brush (z -64..0) both leaves
   * reference, one cluster that sees itself, and a deathmatch spawn above the floor.
   */
  static final class SyntheticMap {
    private static final int VERSION = 46;
    private static final int LUMPS = 17;
    private static final float MIN = -512, MAX = 512, LOW = -256, HIGH = 512;

    private SyntheticMap() {}

    static byte[] bytes() {
      byte[][] lumps = new byte[LUMPS][];
      for (int i = 0; i < LUMPS; i++) lumps[i] = new byte[0];
      lumps[0] =
          ("{\n\"classname\" \"worldspawn\"\n\"message\" \"CraftQ3 download test\"\n}\n"
                  + "{\n\"classname\" \"info_player_deathmatch\"\n\"origin\" \"-200 0 24\"\n}\n"
                  + "{\n\"classname\" \"info_player_deathmatch\"\n\"origin\" \"200 0 24\"\n}\n\0")
              .getBytes(StandardCharsets.ISO_8859_1);
      ByteBuffer texture = buffer(72);
      name(texture, "textures/common/caulk", 64);
      texture.putInt(0).putInt(1);
      lumps[1] = texture.array();
      ByteBuffer planes = buffer(16 * 7);
      plane(planes, 1, 0, 0, 0);
      plane(planes, -1, 0, 0, -MIN);
      plane(planes, 1, 0, 0, MAX);
      plane(planes, 0, -1, 0, -MIN);
      plane(planes, 0, 1, 0, MAX);
      plane(planes, 0, 0, -1, 64);
      plane(planes, 0, 0, 1, 0);
      lumps[2] = planes.array();
      ByteBuffer nodes = buffer(36);
      nodes.putInt(0).putInt(-2).putInt(-1);
      bounds(nodes);
      lumps[3] = nodes.array();
      ByteBuffer leaves = buffer(48 * 2);
      for (int i = 0; i < 2; i++) {
        leaves.putInt(0).putInt(0);
        bounds(leaves);
        leaves.putInt(0).putInt(0).putInt(0).putInt(1);
      }
      lumps[4] = leaves.array();
      lumps[6] = buffer(4).putInt(0).array();
      ByteBuffer models = buffer(40);
      models.putFloat(MIN).putFloat(MIN).putFloat(LOW).putFloat(MAX).putFloat(MAX).putFloat(HIGH);
      models.putInt(0).putInt(0).putInt(0).putInt(1);
      lumps[7] = models.array();
      ByteBuffer brushes = buffer(12);
      brushes.putInt(0).putInt(6).putInt(0);
      lumps[8] = brushes.array();
      ByteBuffer sides = buffer(8 * 6);
      for (int i = 0; i < 6; i++) sides.putInt(1 + i).putInt(0);
      lumps[9] = sides.array();
      ByteBuffer vis = buffer(9);
      vis.putInt(1).putInt(1).put((byte) 1);
      lumps[16] = vis.array();

      int header = 8 + LUMPS * 8;
      int size = header;
      for (byte[] lump : lumps) size += (lump.length + 3) & ~3;
      ByteBuffer out = buffer(size);
      out.put("IBSP".getBytes(StandardCharsets.US_ASCII)).putInt(VERSION);
      int offset = header;
      for (byte[] lump : lumps) {
        out.putInt(offset).putInt(lump.length);
        offset += (lump.length + 3) & ~3;
      }
      for (byte[] lump : lumps) {
        out.put(lump);
        out.position((out.position() + 3) & ~3);
      }
      return out.array();
    }

    private static ByteBuffer buffer(int size) {
      return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static void name(ByteBuffer into, String value, int width) {
      byte[] text = value.getBytes(StandardCharsets.US_ASCII);
      into.put(text);
      into.put(new byte[width - text.length]);
    }

    private static void plane(ByteBuffer out, float x, float y, float z, float d) {
      out.putFloat(x).putFloat(y).putFloat(z).putFloat(d);
    }

    private static void bounds(ByteBuffer out) {
      out.putInt((int) MIN).putInt((int) MIN).putInt((int) LOW);
      out.putInt((int) MAX).putInt((int) MAX).putInt((int) HIGH);
    }
  }
}
