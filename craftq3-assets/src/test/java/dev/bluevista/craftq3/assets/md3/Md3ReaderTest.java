package dev.bluevista.craftq3.assets.md3;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Md3ReaderTest {
  @Test
  void readsAnimatedGeometryAndTagsAndOwnsAllData() throws Exception {
    byte[] input = Md3Fixture.model();
    Md3Model model = Md3Reader.read(input);
    Arrays.fill(input, (byte) 0);
    assertEquals("fixture.md3", model.name());
    assertEquals(7, model.flags());
    assertEquals(2, model.frames().size());
    assertEquals(new Vec3(1, 2, 3), model.frames().get(1).origin());
    assertEquals("frame1", model.frames().get(1).name());
    var surface = model.surfaces().getFirst();
    assertEquals("body_1", surface.name());
    assertEquals(9, surface.flags());
    assertEquals(new Md3Model.Shader("textures/test.tga", 7), surface.shaders().getFirst());
    assertEquals(new Md3Model.Triangle(0, 1, 2), surface.triangles().getFirst());
    assertEquals(new Md3Model.TexCoord(0, 1), surface.texCoords().get(2));
    assertEquals(new Vec3(1, 0, 2), surface.frameVertices().get(1).get(1).position());
    assertEquals(new Vec3(0, 0, 1), surface.frameVertices().getFirst().getFirst().normal());
    assertEquals(new Vec3(0, 2, 4), model.tag(1, "tag_torso").orElseThrow().origin());
    assertTrue(model.tag(0, "missing").isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> surface.triangles().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> surface.frameVertices().getFirst().clear());
    assertThrows(UnsupportedOperationException.class, () -> model.tagFrames().getFirst().clear());
  }

  @Test
  void decodesSignedFixedPointPositionsAndUnitNormals() throws Exception {
    byte[] input = Md3Fixture.model();
    ByteBuffer.wrap(input)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(Md3Fixture.SURFACE + 212, Short.MIN_VALUE)
        .putShort(Md3Fixture.SURFACE + 214, Short.MAX_VALUE);
    Vec3 point =
        Md3Reader.read(input)
            .surfaces()
            .getFirst()
            .frameVertices()
            .getFirst()
            .getFirst()
            .position();
    assertEquals(-512, point.x());
    assertEquals(511.984375, point.y());
    for (int packed = 0; packed < 65536; packed += 61) {
      Vec3 n = Md3Reader.decodeNormal(packed);
      assertEquals(1, n.x() * n.x() + n.y() * n.y() + n.z() * n.z(), 1e-12);
    }
    assertTrue(Md3Reader.decodeNormal(0x0040).x() > .999);
    assertTrue(Md3Reader.decodeNormal(0x4040).y() > .999);
  }

  @Test
  void rejectsMalformedOffsetsCountsIndicesAndFloatingPoint() {
    int[][] corruptions = {
      {0, 0},
      {4, 14},
      {76, 0},
      {76, Integer.MAX_VALUE},
      {80, 17},
      {84, 33},
      {92, 4},
      {92, -1},
      {96, Md3Fixture.FRAMES},
      {104, Integer.MAX_VALUE},
      {Md3Fixture.FRAMES, 0x7fc00000},
      {Md3Fixture.FRAMES + 36, 0xbf800000},
      {Md3Fixture.SURFACE, 0},
      {Md3Fixture.SURFACE + 72, 1},
      {Md3Fixture.SURFACE + 76, 257},
      {Md3Fixture.SURFACE + 80, 4097},
      {Md3Fixture.SURFACE + 84, 8193},
      {Md3Fixture.SURFACE + 88, 108},
      {Md3Fixture.SURFACE + 104, 0},
      {Md3Fixture.SURFACE + 176, -1},
      {Md3Fixture.SURFACE + 176, 3},
      {Md3Fixture.SURFACE + 188, 0x7f800000}
    };
    for (int[] corruption : corruptions) {
      assertThrows(
          Md3FormatException.class,
          () -> Md3Reader.read(Md3Fixture.integer(corruption[0], corruption[1])),
          "field " + corruption[0] + " value " + corruption[1]);
    }
    byte[] changedTag = Md3Fixture.model();
    changedTag[Md3Fixture.TAGS + 112] = 'x';
    assertThrows(Md3FormatException.class, () -> Md3Reader.read(changedTag));
    byte[] controlName = Md3Fixture.model();
    controlName[8] = 1;
    assertThrows(Md3FormatException.class, () -> Md3Reader.read(controlName));
    assertThrows(Md3FormatException.class, () -> Md3Reader.read(null));
  }

  @Test
  void allTruncationsAndRandomHeaderMutationsFailWithCheckedFormatErrors() {
    byte[] source = Md3Fixture.model();
    for (int length = 0; length < source.length; length++) {
      int n = length;
      assertThrows(Md3FormatException.class, () -> Md3Reader.read(Arrays.copyOf(source, n)));
    }
    Random random = new Random(1234);
    for (int i = 0; i < 1000; i++) {
      int field = 76 + 4 * random.nextInt(8);
      assertThrows(
          Md3FormatException.class,
          () -> Md3Reader.read(Md3Fixture.integer(field, random.nextInt())));
    }
  }

  @Test
  void acceptsTagOnlyModelsAndPaddingOutsideDeclaredEnd() throws Exception {
    byte[] tagOnly = Md3Fixture.integer(84, 0);
    ByteBuffer.wrap(tagOnly).order(ByteOrder.LITTLE_ENDIAN).putFloat(Md3Fixture.FRAMES, 99999);
    assertEquals(0, Md3Reader.read(tagOnly).surfaces().size());
    assertFalse(Md3Reader.read(tagOnly).frames().getFirst().hasBounds());
    assertTrue(Md3Reader.read(tagOnly).tag(1, "tag_torso").isPresent());
    byte[] badGeometryBounds = Md3Fixture.integer(Md3Fixture.FRAMES, Float.floatToIntBits(99999));
    assertThrows(Md3FormatException.class, () -> Md3Reader.read(badGeometryBounds));
    assertEquals(1, Md3Reader.read(Arrays.copyOf(Md3Fixture.model(), 800)).surfaces().size());
  }
}
