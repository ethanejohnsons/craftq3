package dev.bluevista.craftq3.engine.game;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.console.CommandParser;
import dev.bluevista.craftq3.engine.fs.PureManifest;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.game.net.Protocol68Host;
import dev.bluevista.craftq3.engine.game.net.PureVerifier;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.net.oob.Protocol68Datagram;
import dev.bluevista.craftq3.engine.net.transport.LoopbackTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PureAndDownloadTest {
  @TempDir Path root;

  private static byte[] zip(Map<String, byte[]> entries) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out = new ZipOutputStream(bytes)) {
      for (var entry : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue());
        out.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private SearchPath install() throws IOException {
    Files.createDirectories(root.resolve("baseq3"));
    Files.createDirectories(root.resolve("mymod"));
    Files.write(root.resolve("baseq3/pak0.pk3"), zip(Map.of("scripts/a.txt", new byte[] {1, 2})));
    byte[] payload = new byte[5000];
    new Random(5).nextBytes(payload);
    Files.write(
        root.resolve("mymod/custom.pk3"),
        zip(
            Map.of(
                "vm/cgame.qvm", payload,
                "vm/ui.qvm", new byte[] {9, 9, 9},
                "maps/readme.txt", new byte[] {7})));
    return SearchPath.mount(root, "mymod");
  }

  @Test
  void pureRepliesAreVerifiedAgainstTheLoadedPacks() throws IOException {
    try (SearchPath path = install()) {
      PureManifest manifest = new PureManifest(path);
      manifest.restart(12345);
      int cgame = PureVerifier.packChecksum(manifest, "vm/cgame.qvm").orElseThrow();
      int ui = PureVerifier.packChecksum(manifest, "vm/ui.qvm").orElseThrow();
      int other =
          manifest.packs().stream()
              .mapToInt(PureManifest.Pack::pureChecksum)
              .filter(c -> c != cgame)
              .findFirst()
              .orElseThrow();
      int encoded = 12345 ^ cgame ^ other ^ 2;
      String good = "cp 100 " + cgame + " " + ui + " @ " + cgame + " " + other + " " + encoded;
      assertEquals(
          PureVerifier.Result.ACCEPTED,
          PureVerifier.verify(CommandParser.tokenize(good), manifest, 100, 12345));
      assertEquals(
          PureVerifier.Result.IGNORED,
          PureVerifier.verify(CommandParser.tokenize(good), manifest, 101, 12345));
      String forged =
          "cp 100 " + cgame + " " + ui + " @ " + cgame + " 77 " + (12345 ^ cgame ^ 77 ^ 2);
      assertEquals(
          PureVerifier.Result.REJECTED,
          PureVerifier.verify(CommandParser.tokenize(forged), manifest, 100, 12345));
      String duplicate =
          "cp 100 " + cgame + " " + ui + " @ " + cgame + " " + cgame + " " + (12345 ^ 2);
      assertEquals(
          PureVerifier.Result.REJECTED,
          PureVerifier.verify(CommandParser.tokenize(duplicate), manifest, 100, 12345));
      String badUi = "cp 100 " + cgame + " " + (ui + 1) + " @ " + cgame + " " + (12345 ^ cgame ^ 1);
      assertEquals(
          PureVerifier.Result.REJECTED,
          PureVerifier.verify(CommandParser.tokenize(badUi), manifest, 100, 12345));
    }
  }

  @Test
  void referencedModPacksDownloadOverTheUdpProtocolAndOfficialPaksAreRefused() throws Exception {
    try (SearchPath path = install()) {
      PureManifest manifest = new PureManifest(path);
      Harness h = new Harness(TestMaps::corridor, manifest);
      h.server.cvars().set("sv_allowDownload", "1");
      h.server.cvars().set("sv_pure", "0");
      h.spawn();
      assertTrue(h.server.cvars().string("sv_referencedPakNames").contains("mymod/custom"));
      Protocol68Host host = new Protocol68Host(h.server, name -> Optional.empty(), new Random(2));
      LoopbackTransport transport = host.openLoopback();
      TestClient client = new TestClient(h, host, transport);
      assertTrue(
          client.connect(
              Map.of(
                  "name",
                  "Downloader",
                  "rate",
                  "25000",
                  "snaps",
                  "20",
                  "model",
                  "sarge/default",
                  "headmodel",
                  "sarge/default",
                  "handicap",
                  "100"),
              9));
      client.stepIdle();

      client.session.command("download baseq3/pak0.pk3");
      var refused = new ByteArrayOutputStream();
      String[] error = {null};
      download(client, refused, error, 20);
      assertNotNull(error[0]);
      assertTrue(error[0].contains("official"), error[0]);

      client.session.command("download mymod/custom.pk3");
      client.session.beginDownload();
      var received = new ByteArrayOutputStream();
      error[0] = null;
      download(client, received, error, 400);
      assertNull(error[0]);
      assertArrayEquals(
          Files.readAllBytes(root.resolve("mymod/custom.pk3")), received.toByteArray());
      client.session.command("donedl");
      client.stepIdle();
      client.stepIdle();
      assertEquals(
          ServerClient.State.PRIMED, h.server.client(0).state(), "the gamestate was sent again");
    }
  }

  /** Runs frames, acknowledging blocks, until the zero-length final block or an error arrives. */
  private static void download(
      TestClient client, ByteArrayOutputStream out, String[] error, int frames) {
    for (int i = 0; i < frames; i++) {
      client.transmit(java.util.List.of());
      client.frame();
      for (byte[] packet : client.poll()) {
        if (Protocol68Datagram.classify(packet) == Protocol68Datagram.Kind.CONNECTIONLESS) continue;
        var received = client.session.receive(packet);
        if (received.message() == null) continue;
        for (var operation : received.message().operations()) {
          if (!(operation instanceof ServerMessageCodec.Download download)) continue;
          if (download.error() != null) {
            error[0] = download.error();
            return;
          }
          out.writeBytes(download.data());
          client.session.command("nextdl " + download.block());
          if (download.data().length == 0) return;
        }
      }
    }
    fail("download did not finish");
  }
}
