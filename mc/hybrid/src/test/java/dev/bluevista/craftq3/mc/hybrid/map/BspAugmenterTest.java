package dev.bluevista.craftq3.mc.hybrid.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.collision.CollisionModel;
import dev.bluevista.craftq3.engine.collision.Contents;
import dev.bluevista.craftq3.engine.collision.Trace;
import dev.bluevista.craftq3.engine.formats.bsp.BspFixture;
import dev.bluevista.craftq3.engine.formats.bsp.BspMap;
import dev.bluevista.craftq3.engine.formats.bsp.BspReader;
import dev.bluevista.craftq3.engine.formats.entity.BspEntity;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.fs.VirtualPath;
import dev.bluevista.craftq3.engine.testkit.RealData;
import dev.bluevista.craftq3.engine.testkit.RealDataRoot;
import dev.bluevista.craftq3.mc.hybrid.bridge.HostTriggers;
import java.util.Random;
import org.junit.jupiter.api.Test;

class BspAugmenterTest {
  @Test
  void copiesEveryLumpOfTheFixturesAndAppendsModels() {
    for (boolean patch : new boolean[] {false, true}) {
      BspMap original = BspReader.read(BspFixture.map(patch), "fixture.bsp");
      BspAugmenter augmenter = new BspAugmenter(original);
      int model =
          augmenter.boxModel(
              HostTriggers.mins(), HostTriggers.maxs(), HostTriggers.SHADER, Contents.TRIGGER);
      assertEquals(original.models().size(), model);
      BspMap copy = augmenter.build("copy.bsp");
      assertSameLumps(original, copy);
      assertEquals(original.models().size() + 1, copy.models().size());
      CollisionModel cm = CollisionModel.build(copy);
      assertEquals(Contents.TRIGGER, cm.pointContents(new float[] {0, 0, 0}, model));
      assertEquals(0, cm.pointContents(new float[] {0, 0, 600}, model));
    }
  }

  @Test
  void replacesTheEntityString() {
    BspMap original =
        new SyntheticBsp().entity(BspEntity.of("classname", "worldspawn")).build("a.bsp");
    String text =
        original.entityText()
            + EntityText.write(HostTriggers.entities(1, new float[] {0, 0, -9000}));
    BspAugmenter augmenter = new BspAugmenter(original).entityText(text);
    for (int i = 0; i < HostTriggers.MODELS; i++)
      augmenter.boxModel(
          HostTriggers.mins(), HostTriggers.maxs(), HostTriggers.SHADER, Contents.TRIGGER);
    BspMap copy = augmenter.build("b.bsp");
    assertEquals(1 + HostTriggers.MODELS, copy.models().size());
    assertEquals(1 + HostTriggers.MODELS + 2, copy.entities().size());
    assertTrue(augmenter.fits(200));
    assertFalse(augmenter.fits(250));
  }

  @RealData
  @Test
  void retailMapKeepsItsCollisionWhenAugmented() throws Exception {
    try (SearchPath search = SearchPath.mount(RealDataRoot.require(), SearchPath.BASE_GAME)) {
      byte[] bytes = search.read(VirtualPath.of("maps/q3dm17.bsp"));
      BspMap original = BspReader.read(bytes, "q3dm17.bsp");
      BspAugmenter augmenter = new BspAugmenter(original);
      int first = augmenter.nextModel();
      for (int i = 0; i < HostTriggers.MODELS; i++)
        augmenter.boxModel(
            HostTriggers.mins(), HostTriggers.maxs(), HostTriggers.SHADER, Contents.TRIGGER);
      BspMap copy = augmenter.build("q3dm17+host.bsp");
      assertSameLumps(original, copy);
      CollisionModel a = CollisionModel.build(original);
      CollisionModel b = CollisionModel.build(copy);
      Random random = new Random(17);
      float[] mins = {-15, -15, -24};
      float[] maxs = {15, 15, 32};
      Trace ta = new Trace();
      Trace tb = new Trace();
      for (int i = 0; i < 5000; i++) {
        float[] s = {
          random.nextFloat() * 4000 - 2000,
          random.nextFloat() * 4000 - 2000,
          random.nextFloat() * 1500 - 300
        };
        float[] e = {
          random.nextFloat() * 4000 - 2000,
          random.nextFloat() * 4000 - 2000,
          random.nextFloat() * 1500 - 300
        };
        a.boxTrace(a.work(), ta, s, e, mins, maxs, 0, Contents.MASK_PLAYERSOLID, false);
        b.boxTrace(b.work(), tb, s, e, mins, maxs, 0, Contents.MASK_PLAYERSOLID, false);
        assertEquals(ta.toString(), tb.toString());
      }
      for (int m = 1; m < original.models().size(); m++) {
        float[] amin = new float[3];
        float[] amax = new float[3];
        float[] bmin = new float[3];
        float[] bmax = new float[3];
        a.modelBounds(m, amin, amax);
        b.modelBounds(m, bmin, bmax);
        assertArrayEquals(amin, bmin);
        assertArrayEquals(amax, bmax);
      }
      assertEquals(Contents.TRIGGER, b.pointContents(new float[] {0, 0, 0}, first));
      assertEquals(original.checksum() == copy.checksum(), false);
    }
  }

  static void assertSameLumps(BspMap a, BspMap b) {
    assertEquals(
        a.planes().count() + 6 * (b.models().size() - a.models().size()), b.planes().count());
    for (int p = 0; p < a.planes().count(); p++) {
      for (int axis = 0; axis < 3; axis++)
        assertEquals(a.planes().normal(p, axis), b.planes().normal(p, axis));
      assertEquals(a.planes().distance(p), b.planes().distance(p));
    }
    assertEquals(a.nodes().count(), b.nodes().count());
    for (int n = 0; n < a.nodes().count(); n++) {
      assertEquals(a.nodes().plane(n), b.nodes().plane(n));
      assertEquals(a.nodes().front(n), b.nodes().front(n));
      assertEquals(a.nodes().back(n), b.nodes().back(n));
    }
    assertEquals(a.leaves().count(), b.leaves().count());
    for (int l = 0; l < a.leaves().count(); l++) {
      assertEquals(a.leaves().cluster(l), b.leaves().cluster(l));
      assertEquals(a.leaves().area(l), b.leaves().area(l));
      assertEquals(a.leaves().firstLeafBrush(l), b.leaves().firstLeafBrush(l));
      assertEquals(a.leaves().leafBrushCount(l), b.leaves().leafBrushCount(l));
      assertEquals(a.leaves().firstLeafFace(l), b.leaves().firstLeafFace(l));
    }
    assertArrayEquals(a.leafFaces().toArray(), b.leafFaces().toArray());
    assertArrayEquals(a.leafBrushes().toArray(), b.leafBrushes().toArray());
    assertArrayEquals(a.meshVertices().toArray(), b.meshVertices().toArray());
    assertArrayEquals(a.vertices().positionsCopy(), b.vertices().positionsCopy());
    assertArrayEquals(a.vertices().colorsCopy(), b.vertices().colorsCopy());
    assertArrayEquals(a.vertices().normalsCopy(), b.vertices().normalsCopy());
    assertEquals(a.faces().count(), b.faces().count());
    for (int f = 0; f < a.faces().count(); f++) {
      assertEquals(a.faces().type(f), b.faces().type(f));
      assertEquals(a.faces().lightmap(f), b.faces().lightmap(f));
      assertEquals(a.faces().patchWidth(f), b.faces().patchWidth(f));
      assertEquals(a.faces().normal(f, 2), b.faces().normal(f, 2));
    }
    assertEquals(a.lightmaps().count(), b.lightmaps().count());
    assertArrayEquals(a.lightGrid().toArray(), b.lightGrid().toArray());
    assertEquals(a.visibility().clusters(), b.visibility().clusters());
    for (int c = 0; c < a.visibility().clusters(); c++)
      assertArrayEquals(a.visibility().row(c), b.visibility().row(c));
    assertEquals(a.effects(), b.effects());
    assertEquals(a.textures(), b.textures().subList(0, a.textures().size()));
    for (int m = 0; m < a.models().size(); m++) assertEquals(a.models().get(m), b.models().get(m));
  }
}
