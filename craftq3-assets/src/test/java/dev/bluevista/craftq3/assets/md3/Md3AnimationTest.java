package dev.bluevista.craftq3.assets.md3;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.assets.md3.AnimationConfig.Animation;
import dev.bluevista.craftq3.core.math.Vec3;
import org.junit.jupiter.api.Test;

class Md3AnimationTest {
  @Test
  void blendsPositionsUnitNormalsAndAttachmentAxes() throws Exception {
    Md3Model model = Md3Reader.read(Md3Fixture.model());
    var blended = Md3Animation.interpolateSurface(model.surfaces().getFirst(), 0, 1, .5f);
    assertEquals(new Vec3(1, 0, 1), blended.get(1).position());
    Vec3 n = blended.getFirst().normal();
    assertEquals(1, n.x() * n.x() + n.y() * n.y() + n.z() * n.z(), 1e-12);
    var tag = Md3Animation.interpolateTag(model, "tag_torso", 0, 1, .5f).orElseThrow();
    assertEquals(new Vec3(0, 1, 4), tag.origin());
    assertEquals(Math.sqrt(.5), tag.axisX().x(), 1e-12);
    assertEquals(Math.sqrt(.5), tag.axisX().y(), 1e-12);
    assertEquals(0, tag.axisX().x() * tag.axisY().x() + tag.axisX().y() * tag.axisY().y(), 1e-12);
    assertTrue(Md3Animation.interpolateTag(model, "missing", 0, 1, 0).isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> Md3Animation.interpolateSurface(model.surfaces().getFirst(), 0, 1, Float.NaN));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> Md3Animation.interpolateSurface(model.surfaces().getFirst(), 0, 2, 0));
    assertThrows(UnsupportedOperationException.class, blended::clear);
  }

  @Test
  void composesMultipartAttachmentsInParentBasis() throws Exception {
    var parent = Md3Reader.read(Md3Fixture.model()).tag(1, "tag_torso").orElseThrow();
    var child =
        new Md3Model.Tag(
            "tag_head", new Vec3(2, 0, 1), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1));
    var head = parent.compose(child);
    assertEquals(new Vec3(0, 4, 5), head.origin());
    assertEquals(new Vec3(0, 1, 0), head.axisX());
    assertEquals(new Vec3(-1, 4, 5), head.transformPoint(new Vec3(0, 1, 0)));
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
