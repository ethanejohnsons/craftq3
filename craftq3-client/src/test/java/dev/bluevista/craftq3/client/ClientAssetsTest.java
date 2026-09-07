package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ClientAssetsTest {
  @Test
  void emptyShaderUsesZeroWithoutRegisteringOrChangingTheAssetSnapshot() throws Exception {
    var messages = new ArrayList<String>();
    var assets =
        new ClientAssets(
            new ClientTestData.Files(Map.of()), new ClientTestData.Audio(), messages::add);
    var snapshot = assets.snapshot();
    int shaders = assets.shaders();
    for (boolean lightmap : new boolean[] {false, true})
      for (boolean model : new boolean[] {false, true})
        assertEquals(0, assets.shader("", lightmap, model));
    assertEquals(shaders, assets.shaders());
    assertSame(snapshot, assets.snapshot());
    assertEquals("white", assets.shader(0));
    assertTrue(assets.shader("white", false, false) > 0);
    assertTrue(messages.isEmpty());
  }

  @Test
  void emptyNameDoesNotSuppressMalformedVirtualPathsOrNullHostRequests() throws Exception {
    var assets =
        new ClientAssets(
            new ClientTestData.Files(Map.of()), new ClientTestData.Audio(), ignored -> {});
    for (String name :
        new String[] {null, "../escape", "/absolute", "x//y", "x\u0000y", "x".repeat(256)})
      assertThrows(IllegalArgumentException.class, () -> assets.shader(name, false, false));
    assertEquals(1, assets.shaders());
  }

  @Test
  void missingNamedImageRetainsItsDiagnosticAndMalformedImageStillFails() throws Exception {
    var messages = new ArrayList<String>();
    var assets =
        new ClientAssets(
            new ClientTestData.Files(Map.of("broken.tga", new byte[18])),
            new ClientTestData.Audio(),
            messages::add);
    int missing = assets.shader("absent", false, false);
    assertTrue(missing > 0);
    assertEquals(missing, assets.shader("ABSENT.TGA", false, false));
    assertEquals("absent", assets.shader(missing));
    assertFalse(messages.isEmpty());
    assertThrows(IOException.class, () -> assets.shader("broken", false, false));
  }
}
