package dev.bluevista.craftq3.engine.fs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ported from the legacy engine-services suite. */
class GameFileHandlesTest {
  @TempDir Path temporary;

  private static FileSource memory(Map<String, String> data) {
    return new FileSource() {
      @Override
      public boolean exists(VirtualPath path) {
        return data.containsKey(path.key());
      }

      @Override
      public byte[] read(VirtualPath path) throws IOException {
        String value = data.get(path.key());
        if (value == null) throw new NoSuchFileException(path.key());
        return value.getBytes(StandardCharsets.ISO_8859_1);
      }
    };
  }

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }

  @Test
  void readSeekAppendPersistAndRejectStaleHandles() throws IOException {
    var store = HomeStore.open(temporary.resolve("game"));
    var fs = memory(Map.of("config.cfg", "base", "vm/cgame.qvm", "module"));
    try (store;
        var handles = new GameFileHandles(fs, store)) {
      var original = handles.open(VirtualPath.of("config.cfg"), GameFileHandles.Mode.READ);
      assertEquals("ba", text(handles.read(original.handle(), 2)));
      handles.seek(original.handle(), -1, GameFileHandles.Seek.END);
      assertEquals("e", text(handles.read(original.handle(), 20)));
      handles.close(original.handle());
      assertThrows(IOException.class, () -> handles.read(original.handle(), 1));
      var append = handles.open(VirtualPath.of("config.cfg"), GameFileHandles.Mode.APPEND_SYNC);
      handles.write(append.handle(), " plus".getBytes(StandardCharsets.ISO_8859_1));
      assertEquals("base plus", Files.readString(temporary.resolve("game/config.cfg")));
      handles.close(append.handle());
      var changed = handles.open(VirtualPath.of("config.cfg"), GameFileHandles.Mode.READ);
      assertEquals(9, changed.length());
      assertTrue(changed.handle() > original.handle());
      assertEquals(
          6, handles.open(VirtualPath.of("vm/cgame.qvm"), GameFileHandles.Mode.READ).length());
      assertThrows(
          IOException.class, () -> handles.seek(changed.handle(), -1, GameFileHandles.Seek.START));
      assertThrows(IOException.class, () -> handles.write(changed.handle(), new byte[0]));
      assertThrows(
          NoSuchFileException.class,
          () -> handles.open(VirtualPath.of("missing.cfg"), GameFileHandles.Mode.READ));
    }
  }

  @Test
  void appendWritesStayAtEndAfterSeekAndEmptyAppendCreatesAFile() throws IOException {
    try (var store = HomeStore.open(temporary.resolve("game"));
        var handles = new GameFileHandles(memory(Map.of("config.cfg", "base")), store)) {
      var opened = handles.open(VirtualPath.of("config.cfg"), GameFileHandles.Mode.APPEND);
      handles.seek(opened.handle(), 0, GameFileHandles.Seek.START);
      handles.write(opened.handle(), new byte[] {'!'});
      handles.close(opened.handle());
      assertEquals("base!", store.readText(VirtualPath.of("config.cfg")));
      var empty = handles.open(VirtualPath.of("empty.log"), GameFileHandles.Mode.APPEND);
      handles.close(empty.handle());
      assertEquals(0, store.read(VirtualPath.of("empty.log")).length);
    }
  }

  @Test
  void writesNeedAStoreAndTheTableIsBounded() throws IOException {
    try (var handles = new GameFileHandles(memory(Map.of("a", "x")), null)) {
      assertThrows(
          IOException.class, () -> handles.open(VirtualPath.of("a"), GameFileHandles.Mode.WRITE));
      for (int i = 0; i < GameFileHandles.MAX_HANDLES; i++)
        handles.open(VirtualPath.of("a"), GameFileHandles.Mode.READ);
      assertEquals(GameFileHandles.MAX_HANDLES, handles.openCount());
      assertThrows(
          IOException.class, () -> handles.open(VirtualPath.of("a"), GameFileHandles.Mode.READ));
    }
  }

  @Test
  void closingTheTableFlushesPendingWritesAndInvalidatesHandles() throws IOException {
    try (var store = HomeStore.open(temporary.resolve("game"))) {
      var handles = new GameFileHandles(memory(Map.of()), store);
      var log = handles.open(VirtualPath.of("logs/games.log"), GameFileHandles.Mode.WRITE);
      handles.write(log.handle(), "line\n".getBytes(StandardCharsets.ISO_8859_1));
      assertFalse(store.exists(VirtualPath.of("logs/games.log")));
      handles.close();
      handles.close();
      assertEquals("line\n", store.readText(VirtualPath.of("logs/games.log")));
      assertThrows(IOException.class, () -> handles.position(log.handle()));
      assertThrows(
          IOException.class, () -> handles.open(VirtualPath.of("x"), GameFileHandles.Mode.READ));
    }
  }

  @Test
  void homeFilesShadowSearchPathFilesAndPackedQvmsCannotBeWritten() throws IOException {
    var root = temporary.resolve("install");
    TestZip.create().add("default.cfg", "packed").write(root.resolve("baseq3/pak0.pk3"));
    try (var fs = SearchPath.mount(root, null);
        var store = HomeStore.open(temporary.resolve("home"));
        var handles = new GameFileHandles(fs, store)) {
      var packed = handles.open(VirtualPath.of("default.cfg"), GameFileHandles.Mode.READ);
      assertEquals("packed", text(handles.read(packed.handle(), 64)));
      store.write(VirtualPath.of("default.cfg"), "home".getBytes(StandardCharsets.ISO_8859_1));
      var home = handles.open(VirtualPath.of("Default.CFG"), GameFileHandles.Mode.READ);
      assertEquals("home", text(handles.read(home.handle(), 64)));
      assertThrows(
          IOException.class,
          () -> handles.open(VirtualPath.of("vm/qagame.qvm"), GameFileHandles.Mode.WRITE));
    }
  }
}
