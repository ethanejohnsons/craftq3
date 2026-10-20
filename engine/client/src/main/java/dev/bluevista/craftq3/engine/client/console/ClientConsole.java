package dev.bluevista.craftq3.engine.client.console;

import dev.bluevista.craftq3.engine.client.input.ClientInput;
import dev.bluevista.craftq3.engine.client.input.KeyTarget;
import dev.bluevista.craftq3.engine.console.CommandBuffer;
import dev.bluevista.craftq3.engine.console.CommandRegistry;
import dev.bluevista.craftq3.engine.console.ConsoleField;
import dev.bluevista.craftq3.engine.console.ConsoleOutput;
import dev.bluevista.craftq3.engine.console.KeyNames;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The drop-down console: the scroll-back of printed lines and the input field ({@code Console_Key}).
 * Enter executes a line starting with {@code /} or {@code \} as a command; other text is a command
 * while disconnected and chat ({@code cmd say}) while in a game. Tab completes, the arrow keys walk
 * the history, page keys scroll.
 *
 * <p>It is also the {@link ConsoleOutput} the VMs and engine print to; every line is forwarded to an
 * optional mirror (the host's log).
 */
public final class ClientConsole implements ConsoleOutput, KeyTarget {
  /** Lines of scroll-back kept. */
  public static final int MAX_LINES = 1024;

  private final Deque<String> lines = new ArrayDeque<>();
  private final ConsoleField field = new ConsoleField();
  private CommandBuffer buffer;
  private BooleanSupplier inGame = () -> false;
  private Consumer<String> mirror = line -> {};
  private int scroll;

  /** Connects the console to the command buffer its input executes on. */
  public void attach(CommandBuffer commandBuffer, BooleanSupplier connected) {
    buffer = Objects.requireNonNull(commandBuffer);
    inGame = Objects.requireNonNull(connected);
  }

  /** Every printed line is also passed to {@code sink}. */
  public void mirror(Consumer<String> sink) {
    mirror = Objects.requireNonNull(sink);
  }

  @Override
  public void print(String line) {
    lines.addLast(line);
    while (lines.size() > MAX_LINES) lines.removeFirst();
    mirror.accept(line);
  }

  /** The scroll-back, oldest first. */
  public List<String> lines() {
    return new ArrayList<>(lines);
  }

  public ConsoleField field() {
    return field;
  }

  /** Lines scrolled back from the bottom. */
  public int scroll() {
    return scroll;
  }

  public void clear() {
    lines.clear();
    scroll = 0;
  }

  @Override
  public void keyEvent(int key, boolean down, int time) {
    if (!down) return;
    if ((key & ClientInput.K_CHAR_FLAG) != 0) {
      int character = key & ~ClientInput.K_CHAR_FLAG;
      if (character >= ' ' && character < 256 && character != 127) field.insert(character);
      return;
    }
    switch (key) {
      case KeyNames.ENTER, KeyNames.KP_ENTER -> enter();
      case KeyNames.TAB -> {
        if (buffer == null) return;
        List<String> matches = field.complete(buffer.registry());
        if (matches.size() > 1) {
          print("]" + field.text());
          for (String match : matches) print("    " + match);
        }
      }
      case KeyNames.BACKSPACE -> field.backspace();
      case KeyNames.DEL -> field.delete();
      case KeyNames.LEFTARROW -> field.left();
      case KeyNames.RIGHTARROW -> field.right();
      case KeyNames.HOME -> field.home();
      case KeyNames.END -> field.end();
      case KeyNames.UPARROW -> field.previous();
      case KeyNames.DOWNARROW -> field.next();
      case KeyNames.PGUP, KeyNames.MWHEELUP -> scroll = Math.min(lines.size(), scroll + 2);
      case KeyNames.PGDN, KeyNames.MWHEELDOWN -> scroll = Math.max(0, scroll - 2);
      default -> {}
    }
  }

  private void enter() {
    String raw = field.text().strip();
    print("]" + raw);
    boolean command = raw.startsWith("/") || raw.startsWith("\\");
    var text = field.accept();
    scroll = 0;
    if (text.isEmpty() || buffer == null) return;
    if (command || !inGame.getAsBoolean()) buffer.add(text.get() + "\n");
    else buffer.add("cmd say " + text.get() + "\n");
  }

  /** Executes {@code line} immediately on {@code registry}, as typed into the console. */
  public static boolean execute(CommandRegistry registry, String line) {
    return registry.execute(line);
  }
}
