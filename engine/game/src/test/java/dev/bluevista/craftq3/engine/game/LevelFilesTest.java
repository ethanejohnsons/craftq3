package dev.bluevista.craftq3.engine.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bluevista.craftq3.engine.vmabi.service.VmFiles;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The game files stay usable across levels; only each module instance's handles are closed. */
final class LevelFilesTest {
  /** Like {@code FsVmFiles}: {@code closeAll} retires the table for good. */
  static final class RetiringFiles implements VmFiles {
    final Set<Integer> open = new HashSet<>();
    final List<Integer> closed = new ArrayList<>();
    boolean retired;
    int next = 1;

    @Override
    public int length(String path) {
      return path.equals("bots.txt") ? 3 : -1;
    }

    @Override
    public Opened open(String path, Mode mode) throws IOException {
      if (retired) throw new IOException("retired");
      if (length(path) < 0) throw new NoSuchFileException(path);
      open.add(next);
      return new Opened(next++, 3);
    }

    @Override
    public byte[] read(int handle, int length) {
      return new byte[0];
    }

    @Override
    public int write(int handle, byte[] data) {
      return 0;
    }

    @Override
    public boolean seek(int handle, int offset, Origin origin) {
      return true;
    }

    @Override
    public void close(int handle) throws IOException {
      if (!open.remove(handle)) throw new IOException("bad handle " + handle);
      closed.add(handle);
    }

    @Override
    public List<String> list(String directory, String extension) {
      return List.of();
    }

    @Override
    public void closeAll() {
      open.clear();
      retired = true;
    }
  }

  @Test
  void endingAnInstanceClosesOnlyItsOwnHandles() throws IOException {
    RetiringFiles base = new RetiringFiles();
    LevelFiles files = new LevelFiles(base);
    int a = files.open("bots.txt", VmFiles.Mode.READ).handle();
    int b = files.open("bots.txt", VmFiles.Mode.READ).handle();
    files.close(a);
    assertEquals(1, files.openCount());
    files.closeOpen();
    assertEquals(List.of(a, b), base.closed);
    assertFalse(base.retired);
    // The next instance reads again.
    assertEquals(3, files.open("bots.txt", VmFiles.Mode.READ).length());
    files.closeAll();
    assertTrue(base.retired);
    assertThrows(IOException.class, () -> files.open("bots.txt", VmFiles.Mode.READ));
  }

  @Test
  void theServerKeepsItsFilesAcrossMapChangesAndRestarts() throws IOException {
    RetiringFiles base = new RetiringFiles();
    GameServer server = new Harness(base).server;
    assertTrue(server.spawn("corridor", false));
    assertTrue(server.spawn("other", false));
    server.mapRestart(0);
    assertEquals(GameServer.State.RUNNING, server.state());
    assertFalse(base.retired, "a level change or restart must not retire the game's files");
    server.close();
    assertTrue(base.retired);
  }
}
