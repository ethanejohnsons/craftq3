package dev.bluevista.craftq3.engine.formats.md3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.formats.md3.AnimationConfig.Animation;
import org.junit.jupiter.api.Test;

class Md3AnimationTest {
  @Test
  void blendsPositionsUnitNormalsAndAttachmentAxes() {
    Md3Model model = Md3Reader.read(Md3Fixture.model());
    Md3Model.Surface surface = model.surfaces().getFirst();
    float[] positions = new float[surface.vertexCount() * 3];
    float[] normals = new float[positions.length];
    Md3Animation.interpolateSurface(surface, 0, 1, .5f, positions, normals);
    assertEquals(1, positions[3]);
    assertEquals(0, positions[4]);
    assertEquals(1, positions[5]);
    assertEquals(1, new Vec3f(normals[0], normals[1], normals[2]).length(), 1e-6);
    var tag = Md3Animation.interpolateTag(model, "tag_torso", 0, 1, .5f).orElseThrow();
    assertEquals(new Vec3f(0, 1, 4), tag.origin());
    assertEquals(Math.sqrt(.5), tag.axisX().x(), 1e-6);
    assertEquals(Math.sqrt(.5), tag.axisX().y(), 1e-6);
    assertEquals(0, tag.axisX().dot(tag.axisY()), 1e-6);
    assertTrue(Md3Animation.interpolateTag(model, "missing", 0, 1, 0).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> Md3Animation.interpolateSurface(surface, 0, 1, Float.NaN, positions, normals));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> Md3Animation.interpolateSurface(surface, 0, 2, 0, positions, normals));
  }

  @Test
  void composesMultipartAttachmentsInParentBasis() {
    var parent = Md3Reader.read(Md3Fixture.model()).tag(1, "tag_torso").orElseThrow();
    var child =
        new Md3Model.Tag(
            "tag_head",
            new Vec3f(2, 0, 1),
            new Vec3f(1, 0, 0),
            new Vec3f(0, 1, 0),
            new Vec3f(0, 0, 1));
    var head = parent.compose(child);
    assertEquals(new Vec3f(0, 4, 5), head.origin());
    assertEquals(new Vec3f(0, 1, 0), head.axisX());
    assertEquals(new Vec3f(-1, 4, 5), head.transformPoint(new Vec3f(0, 1, 0)));
  }

  @Test
  void samplesFullFirstPassThenLoopTailAndNonloopingHold() {
    Animation clip = new Animation(10, 4, 2, 2, false, false);
    assertBlend(clip, 0, 10, 11, 0, false);
    assertBlend(clip, .25, 10, 11, .5f, false);
    assertBlend(clip, 1.75, 13, 12, .5f, false);
    assertBlend(clip, 2, 12, 13, 0, false);
    assertBlend(clip, 3, 12, 13, 0, false);
    Animation death = new Animation(10, 4, 0, 2, false, false);
    assertBlend(death, 1.75, 13, 13, 0, false);
    assertBlend(death, 2, 13, 13, 0, true);
    assertBlend(death, Double.MAX_VALUE, 13, 13, 0, true);
    var huge = Md3Animation.sample(clip, Double.MAX_VALUE);
    assertTrue(huge.from() >= 12 && huge.from() <= 13);
    assertThrows(IllegalArgumentException.class, () -> Md3Animation.sample(clip, -1));
    assertThrows(IllegalArgumentException.class, () -> Md3Animation.sample(clip, Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> Md3Animation.sample(new Animation(0, 0, 0, 20, false, false), 0));
  }

  @Test
  void supportsReversedFlipFlopAndSingleFrameClips() {
    assertBlend(new Animation(10, 4, 0, 2, true, false), .25, 13, 12, .5f, false);
    Animation pingPong = new Animation(10, 3, 0, 1, false, true);
    int[] frames = {10, 11, 12, 12, 11, 10, 10};
    for (int i = 0; i < frames.length; i++)
      assertEquals(frames[i], Md3Animation.sample(pingPong, i).from());
    assertBlend(new Animation(2, 1, 1, 30, false, false), 100, 2, 2, 0, false);
  }

  private static void assertBlend(
      Animation animation, double seconds, int from, int to, float fraction, boolean finished) {
    assertEquals(
        new Md3Animation.FrameBlend(from, to, fraction, finished),
        Md3Animation.sample(animation, seconds));
  }
}
