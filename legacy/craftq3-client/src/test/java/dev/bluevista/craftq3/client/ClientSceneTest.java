package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.shader.ShaderDefinition;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.VmAbi;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClientSceneTest {
  @Test
  void registrationsKeepExplicitMaterialsAndUseCorrectImplicitModelAndHudModes() throws Exception {
    var fs =
        new ClientTestData.Files(
            Map.of(
                "scripts/test.shader",
                ("textures/test/explicit {\n {\n map $whiteimage\n blendFunc add\n rgbGen identity\n }\n}")
                    .getBytes(StandardCharsets.US_ASCII)));
    var assets = new ClientAssets(fs, new ClientTestData.Audio(), s -> {});
    int hud = assets.shader("Textures/Test/Hud.tga", false, false);
    assertEquals(hud, assets.shader("textures/test/hud", false, false));
    assets.shader("textures/test/model", false, true);
    assets.shader("textures/test/explicit", false, true);
    var scene = assets.snapshot();
    var overlay = scene.shaders().get("textures/test/hud").stages().getFirst();
    assertEquals(ShaderDefinition.Blend.ALPHA, overlay.blend());
    assertEquals(ShaderDefinition.RgbGenType.VERTEX, overlay.rgbGen().type());
    assertEquals(ShaderDefinition.AlphaGenType.VERTEX, overlay.alphaGen().type());
    assertFalse(overlay.depthWrite());
    assertEquals(
        ShaderDefinition.RgbGenType.LIGHTING_DIFFUSE,
        scene.shaders().get("textures/test/model").stages().getFirst().rgbGen().type());
    assertEquals(
        ShaderDefinition.RgbGenType.IDENTITY,
        scene.shaders().get("textures/test/explicit").stages().getFirst().rgbGen().type());
    assertThrows(IllegalArgumentException.class, () -> assets.shader(999));
    assertThrows(IllegalArgumentException.class, () -> assets.model(-1));
    assertThrows(IllegalArgumentException.class, () -> assets.sound("../escape.wav"));
  }

  @Test
  void copiedViewsPolygonsAndHudKeepCallOrderAfterGuestMemoryChanges() throws Exception {
    var assets =
        new ClientAssets(new ClientTestData.Files(Map.of()), new ClientTestData.Audio(), s -> {});
    var scene = new ClientScene(assets);
    var memory = ClientTestData.memory();
    memory.writeFloat(100, 1);
    memory.writeFloat(104, .5f);
    memory.writeFloat(108, 0);
    memory.writeFloat(112, .25f);
    scene.color(memory, 100);
    scene.quad(new int[] {0, 0, bits(40), bits(20), 0, 0, bits(1), bits(1), 0});
    for (int i = 0; i < 3; i++) {
      int p = 200 + i * 24;
      VmAbi.vector(memory, p, new Vec3(i, i == 2 ? 1 : 0, 0));
      memory.writeFloat(p + 12, i * .5f);
      memory.writeInt(p + 20, -1);
    }
    scene.poly(memory, 0, 3, 200);
    memory.writeInt(1000 + 8, 640);
    memory.writeInt(1000 + 12, 480);
    memory.writeFloat(1016, 90);
    memory.writeFloat(1020, 74);
    VmAbi.vector(memory, 1036, new Vec3(1, 0, 0));
    VmAbi.vector(memory, 1048, new Vec3(0, 1, 0));
    VmAbi.vector(memory, 1060, new Vec3(0, 0, 1));
    memory.writeInt(1072, 1000);
    scene.render(memory, 1000);
    scene.color(memory, 0);
    scene.quad(new int[] {bits(20), 0, bits(4), bits(4), 0, 0, bits(1), bits(1), 0});
    var frame = scene.frame();
    memory.fill(0, 1200, 0);
    scene.beginFrame();
    assertEquals(3, frame.commands().size());
    assertEquals(0xff800040, ((CgameFrame.Quad) frame.commands().getFirst()).rgba());
    var view = (CgameFrame.View) frame.commands().get(1);
    assertEquals(640, view.refdef().width());
    assertEquals(1000, view.refdef().timeMillis());
    assertEquals(new Vec3(2, 1, 0), view.polygons().getFirst().vertices().get(2).position());
    assertEquals(-1, ((CgameFrame.Quad) frame.commands().getLast()).rgba());
    assertThrows(UnsupportedOperationException.class, () -> view.polygons().clear());
    assertTrue(scene.frame().commands().isEmpty());
  }

  @Test
  void malformedSceneInputsFailBeforeAllocationOrSubmission() throws Exception {
    var scene =
        new ClientScene(
            new ClientAssets(
                new ClientTestData.Files(Map.of()), new ClientTestData.Audio(), s -> {}));
    var memory = ClientTestData.memory();
    assertThrows(IllegalArgumentException.class, () -> scene.poly(memory, 0, Integer.MAX_VALUE, 0));
    assertThrows(
        dev.bluevista.craftq3.vm.QvmException.class,
        () -> scene.poly(memory, 0, 3, memory.size() - 24));
    memory.writeFloat(100, Float.NaN);
    assertThrows(IllegalArgumentException.class, () -> scene.color(memory, 100));
    memory.writeInt(100, 99);
    assertThrows(IllegalArgumentException.class, () -> scene.entity(memory, 100));
    assertTrue(scene.frame().commands().isEmpty());
  }

  private static int bits(float value) {
    return Float.floatToRawIntBits(value);
  }
}
