package dev.bluevista.craftq3.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.client.input.ConsoleEditor;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ConsoleEditorTest {
  @Test
  void editsAtCursorAndRestoresTheUnsubmittedHistoryDraft() {
    var editor = new ConsoleEditor();
    type(editor, "map q3dm7");
    editor.left();
    editor.insert('1');
    assertEquals("map q3dm17", editor.accept().orElseThrow());
    type(editor, "fraglimit 20");
    editor.accept();
    type(editor, "unfinished");
    editor.previous();
    assertEquals("fraglimit 20", editor.text());
    editor.previous();
    assertEquals("map q3dm17", editor.text());
    editor.next();
    editor.next();
    assertEquals("unfinished", editor.text());
    editor.home();
    editor.delete();
    editor.insert('U');
    editor.end();
    editor.backspace();
    assertEquals("Unfinishe", editor.text());
  }

  @Test
  void completionPreservesArgumentsAndUsesOnlyTheFirstToken() {
    var editor = new ConsoleEditor();
    type(editor, "ma q3dm17");
    editor.home();
    editor.right();
    editor.right();
    editor.complete(List.of("map", "map_restart"));
    assertEquals("map q3dm17", editor.text());
    editor.home();
    editor.right();
    editor.complete(List.of("map"));
    assertEquals(
        "map q3dm17", editor.text(), "Completing inside a token must not duplicate its suffix");
    editor.end();
    assertTrue(editor.completionPrefix().isEmpty());
    editor.accept();
    type(editor, "\\cg_fo");
    editor.complete(List.of("cg_fov"));
    assertEquals("\\cg_fov ", editor.text());
    assertEquals("cg_fov", editor.accept().orElseThrow());
  }

  @Test
  void untrustedPastesCannotInsertCommandsOrGrowAnUnboundedField() {
    var editor = new ConsoleEditor();
    editor.insert('\n');
    editor.insert(0);
    editor.insert(1000);
    assertEquals("", editor.text());
    for (int i = 0; i < 2048; i++) editor.insert('x');
    assertEquals(1024, editor.text().length());
    assertEquals(1024, editor.cursor());
    editor.accept();
    for (int i = 0; i < 100; i++) {
      type(editor, "echo " + i);
      editor.accept();
    }
    for (int i = 0; i < 100; i++) editor.previous();
    assertEquals("echo 36", editor.text());
  }

  private static void type(ConsoleEditor editor, String text) {
    text.chars().forEach(editor::insert);
  }
}
