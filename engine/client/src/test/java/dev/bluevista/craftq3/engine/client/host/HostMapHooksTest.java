package dev.bluevista.craftq3.engine.client.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.audio.AudioDevice;
import dev.bluevista.craftq3.engine.audio.NullAudioDevice;
import dev.bluevista.craftq3.engine.client.ClientHostServices;
import dev.bluevista.craftq3.engine.client.RenderSink;
import dev.bluevista.craftq3.engine.client.console.ClientConsole;
import dev.bluevista.craftq3.engine.collision.BoxSource;
import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.CollisionSource;
import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.common.thread.ThreadOwner;
import dev.bluevista.craftq3.engine.common.time.ManualClock;
import dev.bluevista.craftq3.engine.console.ConfigSink;
import dev.bluevista.craftq3.engine.console.Console;
import dev.bluevista.craftq3.engine.formats.bsp.BspFixture;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.render.RenderScene;
import dev.bluevista.craftq3.engine.vmabi.service.TimeServices;
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
 * The host's map hooks: {@link ClientHostServices#mapOverride} supplies a map that is not on disk
 * to collision, the renderer's world and render-scene building; {@link
 * ClientHostServices#worldSources} joins foreign geometry into the client's world traces and point
 * contents (cgame prediction).
 */
final class HostMapHooksTest {
  private static final String BRIDGE = "maps/craftq3_bridge.bsp";
  private static final float[] BOX_MIN = {984, 984, 984}, BOX_MAX = {1016, 1016, 1016};

  @TempDir Path install;

  /** A host with an optional override and sources; records the names it was asked about. */
  private static final class Host implements ClientHostServices {
    final BspMap override;
    final List<CollisionSource> sources;
    final List<String> asked = new ArrayList<>();
    final AudioDevice audio = new NullAudioDevice(16, false);

    Host(BspMap override, List<CollisionSource> sources) {
      this.override = override;
      this.sources = sources;
    }

    @Override
    public RenderSink renderSink() {
      return frame -> {};
    }

    @Override
    public AudioDevice audioDevice() {
      return audio;
    }

    @Override
    public int windowWidth() {
      return 640;
    }

    @Override
    public int windowHeight() {
      return 480;
    }

    @Override
    public Optional<BspMap> mapOverride(String normalizedPath) {
      asked.add("override " + normalizedPath);
      return normalizedPath.equals(BRIDGE) ? Optional.ofNullable(override) : Optional.empty();
    }

    @Override
    public List<CollisionSource> worldSources(String mapName) {
      asked.add("sources " + mapName);
      return sources;
    }
  }

  private HostServices services(SearchPath path, Host host) {
    ThreadOwner owner = new ThreadOwner("test client");
    owner.claim();
    ClientConsole output = new ClientConsole();
    Console console = Console.client(owner, output, p -> Optional.empty(), ConfigSink.NONE);
    return new HostServices(
        console,
        output,
        path.view(),
        null,
        install,
        host,
        new TimeServices(new ManualClock(0), java.time.Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
        false);
  }

  private SearchPath mount() throws Exception {
    Files.createDirectories(install.resolve("baseq3"));
    return SearchPath.mount(install, "baseq3");
  }

  @Test
  void anOverriddenMapLoadsWithoutAFileAndFeedsTheRenderer() throws Exception {
    BspMap bridge = BspReader.read(BspFixture.map(false), BRIDGE);
    Host host = new Host(bridge, List.of());
    try (SearchPath path = mount()) {
      HostServices services = services(path, host);
      ClientWorld world = services.loadWorld(BRIDGE);
      assertSame(bridge, world.map(), "collision uses the host's map");
      assertSame(bridge, services.assets().world().orElseThrow(), "so does the renderer");
      assertEquals(BRIDGE, services.assets().worldName());
      assertEquals(List.of("override " + BRIDGE, "sources craftq3_bridge"), host.asked);
      RenderScene scene = RenderScene.build(BRIDGE, bridge, services.assets().library()::resolve);
      assertNotNull(scene.world());
      assertSame(world, services.loadWorld(BRIDGE), "loaded once");
      assertThrows(java.io.IOException.class, () -> services.loadWorld("maps/missing.bsp"));
      services.close();
    }
  }

  @Test
  void worldSourcesJoinWorldTracesAndPointContents() throws Exception {
    BspMap bridge = BspReader.read(BspFixture.map(false), BRIDGE);
    BoxSource terrain =
        BoxSource.builder().add(BOX_MIN.clone(), BOX_MAX.clone(), Contents.SOLID, 0).build();
    try (SearchPath path = mount()) {
      HostServices plain = services(path, new Host(bridge, List.of()));
      ClientWorld without = plain.loadWorld(BRIDGE);
      HostServices joined = services(path, new Host(bridge, List.of(terrain)));
      ClientWorld with = joined.loadWorld(BRIDGE);

      float[] from = {1000, 1000, 1100}, to = {1000, 1000, 900};
      float[] mins = {-15, -15, -24}, maxs = {15, 15, 32};
      int world = with.inlineModel(0);
      assertEquals(CollisionModel.WORLD_MODEL, world);
      float clear =
          without
              .trace(from, to, mins, maxs, world, Contents.MASK_SOLID, null, null, false)
              .fraction();
      float hit =
          with.trace(from, to, mins, maxs, world, Contents.MASK_SOLID, null, null, false)
              .fraction();
      assertTrue(hit < clear, "the terrain stops the trace: " + hit + " vs " + clear);
      float[] zero = new float[3];
      float transformedHit =
          with.trace(from, to, mins, maxs, world, Contents.MASK_SOLID, zero, zero, false)
              .fraction();
      assertEquals(hit, transformedHit, "a transformed world trace at the origin joins too");

      float[] inside = {1000, 1000, 1000};
      assertEquals(0, without.pointContents(inside, world, null, null) & Contents.SOLID);
      assertEquals(Contents.SOLID, with.pointContents(inside, world, null, null) & Contents.SOLID);
      assertEquals(Contents.SOLID, with.pointContents(inside, world, zero, zero) & Contents.SOLID);
      plain.close();
      joined.close();
    }
  }
}
