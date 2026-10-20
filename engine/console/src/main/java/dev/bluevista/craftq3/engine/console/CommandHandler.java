package dev.bluevista.craftq3.engine.console;

/** A registered console command ({@code xcommand_t}). Runs on the console's owner thread. */
@FunctionalInterface
public interface CommandHandler {
  void execute(CommandArgs args);
}
