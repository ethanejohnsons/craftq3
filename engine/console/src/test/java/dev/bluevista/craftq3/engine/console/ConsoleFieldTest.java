package dev.bluevista.craftq3.engine.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConsoleFieldTest {
  private static void type(ConsoleField field, String text) {
    text.chars().forEach(field::insert);
  }

  @Test
  void editingHistoryAndAcceptStripsTheLeadingSlash() {
    var field = new ConsoleField();
    type(field, "/echo hi");
    field.left();
    field.backspace();
    field.end();
    type(field, "!");
    assertEquals("/echo i!", field.text());
    assertEquals(Optional.of("echo i!"), field.accept());
    assertEquals("", field.text());
    type(field, "draft");
    field.previous();
    assertEquals("/echo i!", field.text());
    field.next();
    assertEquals("draft", field.text());
    field.clear();
    assertEquals(Optional.empty(), field.accept());
    field.insert('\n');
    field.insert(300);
    assertEquals("", field.text());
    type(field, "x".repeat(ConsoleField.LIMIT + 5));
    assertEquals(ConsoleField.LIMIT, field.text().length());
  }

  @Test
  void tabCompletesTheTokenBeforeTheCursor() {
    var t = new TestConsole();
    t.cvars().register("sensitivity", "5", CvarFlags.ARCHIVE);
    var field = new ConsoleField();
    type(field, "/sensi");
    assertEquals(List.of("sensitivity"), field.complete(t.commands()));
    assertEquals("/sensitivity ", field.text());

    field.clear();
    type(field, "bind mouse");
    var matches = field.complete(t.commands());
    assertEquals(5, matches.size());
    assertEquals("bind MOUSE", field.text());

    field.clear();
    type(field, "echo a; vstr sens");
    field.complete(t.commands());
    assertEquals("echo a; vstr sensitivity ", field.text());
  }
}
