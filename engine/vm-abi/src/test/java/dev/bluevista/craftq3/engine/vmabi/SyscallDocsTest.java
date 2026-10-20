package dev.bluevista.craftq3.engine.vmabi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code docs/SYSCALLS.md} generated from the import enums: when the committed file differs
 * from {@link SyscallDocs#render()}, the test rewrites it and fails so the change gets committed.
 */
final class SyscallDocsTest {
  @Test
  void committedSyscallTableIsCurrent() throws IOException {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.exists(root.resolve("settings.gradle"))) root = root.getParent();
    assertNotNull(root, "repository root not found");
    Path file = root.resolve("docs/SYSCALLS.md");
    String rendered = SyscallDocs.render();
    String committed = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    if (!committed.equals(rendered)) {
      Files.writeString(file, rendered, StandardCharsets.UTF_8);
      fail("docs/SYSCALLS.md was stale and has been regenerated; commit it");
    }
  }

  @Test
  void documentationListsEveryImport() {
    String text = SyscallDocs.render();
    for (VmModule module : VmModule.values())
      for (VmImport entry : ImportTable.canonicalEntries(module))
        assertTrue(text.contains("`" + entry.name() + "`"), entry.name());
    for (RetailImport entry : RetailImport.values())
      assertTrue(text.contains("`" + entry.name() + "`"), entry.name());
  }
}
