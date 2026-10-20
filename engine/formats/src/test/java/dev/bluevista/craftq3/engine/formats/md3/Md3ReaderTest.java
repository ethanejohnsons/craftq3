package dev.bluevista.craftq3.engine.formats.md3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Md3ReaderTest {
  @Test
  void readsAnimatedGeometryAndTagsAndOwnsAllData() {
    byte[] input = Md3Fixture.model();
    Md3Model model = Md3Reader.read(input);
    Arrays.fill(input, (byte) 0);
    assertEquals("fixture.md3", model.name());
    assertEquals(7, model.flags());
    assertEquals(2, model.frameCount());
    assertEquals(new Vec3f(1, 2, 3), model.frames().get(1).origin());
    assertEquals("frame1", model.frames().get(1).name());
    Md3Model.Surface surface = model.surfaces().getFirst();
    assertEquals("body_1", surface.name());
    assertEquals(9, surface.flags());
    assertEquals(new Md3Model.Shader("textures/test.tga", 7), surface.shaders().getFirst());
    assertEquals(1, surface.triangleCount());
    assertEquals(2, surface.triangle(0, 2));
    assertEquals(1, surface.texCoord(2, 1));
    assertEquals(new Vec3f(1, 0, 2), surface.position(1, 1));
    assertEquals(0, surface.normal(0, 0).x(), 1e-5);
    assertEquals(1, surface.normal(0, 0).z(), 1e-5);
    assertEquals(new Vec3f(0, 2, 4), model.tag(1, "tag_torso").orElseThrow().origin());
    assertEquals("tag_torso", model.tagName(1, 0));
    assertTrue(model.tag(0, "missing").isEmpty());
    int[] triangles = surface.trianglesCopy();
    triangles[0] = 5;
    assertEquals(0, surface.triangle(0, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> surface.position(2, 0));
  }

  @Test
  void decodesSignedFixedPointPositionsAndRendererNormals() {
    byte[] input = Md3Fixture.model();
    ByteBuffer.wrap(input)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(Md3Fixture.SURFACE + 212, Short.MIN_VALUE)
        .putShort(Md3Fixture.SURFACE + 214, Short.MAX_VALUE);
    Vec3f point = Md3Reader.read(input).surfaces().getFirst().position(0, 0);
    assertEquals(-512, point.x());
    assertEquals(511.984375f, point.y());
    for (int packed = 0; packed < 65536; packed += 61) {
      Vec3f n = Md3Reader.decodeNormal(packed);
      // The renderer's 1023-step sine table makes decoded normals unit length to about 0.5%.
      assertEquals(1, n.length(), 5e-3, Integer.toHexString(packed));
    }
    assertTrue(Md3Reader.decodeNormal(0x0040).x() > .999);
    assertTrue(Md3Reader.decodeNormal(0x4040).y() > .999);
    assertEquals(1, Md3Reader.decodeNormal(0).z(), 1e-5);
  }

  @Test
  void rejectsMalformedOffsetsCountsIndicesAndFloatingPoint() {
    int[][] corruptions = {
      {0, 0},
      {4, 14},
      {76, 0},
      {76, Integer.MAX_VALUE},
      {80, Md3Reader.MAX_TAGS + 1},
      {84, Md3Reader.MAX_SURFACES + 1},
      {92, -1},
      {92, Md3Fixture.END},
      {104, Md3Fixture.END + 1},
      {Md3Fixture.SURFACE, 0},
      {Md3Fixture.SURFACE + 76, 257},
      {Md3Fixture.SURFACE + 80, 4097},
      {Md3Fixture.SURFACE + 84, 8193},
      {Md3Fixture.SURFACE + 88, 108},
      {Md3Fixture.SURFACE + 104, 0},
      {Md3Fixture.SURFACE + 176, -1},
      {Md3Fixture.SURFACE + 176, 3}
    };
    for (int[] corruption : corruptions) {
      assertThrows(
          Md3FormatException.class,
          () -> Md3Reader.read(Md3Fixture.integer(corruption[0], corruption[1])),
          "field " + corruption[0] + " value " + corruption[1]);
    }
    assertThrows(Md3FormatException.class, () -> Md3Reader.read(null));
  }

  @Test
  void trustsTheExporterLikeTheOriginalLoader() {
    // Negative radius, renamed tags between frames, a control character in the name and a surface
    // with a different frame count are all accepted.
    Md3Model radius = Md3Reader.read(Md3Fixture.integer(Md3Fixture.FRAMES + 36, 0xbf800000));
    assertEquals(-1, radius.frames().getFirst().radius());
    byte[] renamed = Md3Fixture.model();
    renamed[Md3Fixture.TAGS + 112] = 'x';
    Md3Model model = Md3Reader.read(renamed);
    assertTrue(model.tag(1, "tag_torso").isEmpty());
    assertTrue(model.tag(1, "xag_torso").isPresent());
    byte[] control = Md3Fixture.model();
    control[8] = 1;
    assertEquals(1, Md3Reader.read(control).name().charAt(0));
    Md3Model frames = Md3Reader.read(Md3Fixture.integer(Md3Fixture.SURFACE + 72, 1));
    assertEquals(1, frames.surfaces().getFirst().frameCount());
    assertFalse(frames.diagnostics().isEmpty());
    for (int[] nonFinite :
        new int[][] {{Md3Fixture.FRAMES, 0x7fc00000}, {Md3Fixture.SURFACE + 188, 0x7f800000}}) {
      Md3Model sanitised = Md3Reader.read(Md3Fixture.integer(nonFinite[0], nonFinite[1]));
      assertTrue(sanitised.diagnostics().stream().anyMatch(d -> d.contains("Non-finite")));
    }
    Md3Model coords = Md3Reader.read(Md3Fixture.integer(Md3Fixture.SURFACE + 188, 0x7f800000));
    assertEquals(0, coords.surfaces().getFirst().texCoord(0, 0));
  }

  @Test
  void allTruncationsAndRandomHeaderMutationsFailOnlyWithFormatErrors() {
    byte[] source = Md3Fixture.model();
    for (int length = 0; length < Md3Fixture.END; length++) {
      int n = length;
      assertThrows(Md3FormatException.class, () -> Md3Reader.read(Arrays.copyOf(source, n)));
    }
    Random random = new Random(1234);
    for (int i = 0; i < 1000; i++) {
      int field = 76 + 4 * random.nextInt(8);
      try {
        Md3Reader.read(Md3Fixture.integer(field, random.nextInt()));
      } catch (Md3FormatException expected) {
        assertTrue(expected.getMessage() != null);
      }
    }
  }

  @Test
  void acceptsTagOnlyModelsAndPaddingOutsideDeclaredEnd() {
    byte[] tagOnly = Md3Fixture.integer(84, 0);
    ByteBuffer.wrap(tagOnly).order(ByteOrder.LITTLE_ENDIAN).putFloat(Md3Fixture.FRAMES, 99999);
    Md3Model model = Md3Reader.read(tagOnly);
    assertEquals(0, model.surfaces().size());
    assertFalse(model.frames().getFirst().hasBounds());
    assertTrue(model.diagnostics().isEmpty());
    assertTrue(model.tag(1, "tag_torso").isPresent());
    Md3Model inverted =
        Md3Reader.read(Md3Fixture.integer(Md3Fixture.FRAMES, Float.floatToIntBits(99999)));
    assertTrue(inverted.diagnostics().stream().anyMatch(d -> d.contains("inverted bounds")));
    assertEquals(1, Md3Reader.read(Arrays.copyOf(Md3Fixture.model(), 800)).surfaces().size());
  }
}
