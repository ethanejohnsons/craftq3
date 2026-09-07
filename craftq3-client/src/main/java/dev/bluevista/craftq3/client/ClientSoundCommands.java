package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.command.CommandSystem;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Engine-owned local sound commands, independent of guest console command registration. */
final class ClientSoundCommands implements AutoCloseable {
  private static final int CHAN_LOCAL_SOUND = 6;
  private final CommandSystem commands;
  private final ClientAssets assets;
  private final AudioBackend audio;
  private final IntSupplier entity;
  private final Consumer<String> output;
  private boolean ownsPlay;

  ClientSoundCommands(
      CommandSystem commands,
      ClientAssets assets,
      AudioBackend audio,
      IntSupplier entity,
      Consumer<String> output) {
    this.commands = commands;
    this.assets = assets;
    this.audio = audio;
    this.entity = entity;
    this.output = output;
    if (!commands.complete("play").contains("play")) {
      commands.register("play", this::play);
      ownsPlay = true;
    }
  }

  private void play(CommandParser.Command command) {
    if (command.arguments().size() < 2) {
      output.accept("Usage: play <sound filename> [sound filename] ...\n");
      return;
    }
    for (String name : command.arguments().subList(1, command.arguments().size())) {
      try {
        int sound = assets.sound(name);
        if (sound != 0)
          audio.play(
              new AudioBackend.Playback(
                  sound,
                  entity.getAsInt(),
                  CHAN_LOCAL_SOUND,
                  AudioBackend.Spatial.LOCAL,
                  null,
                  1,
                  1));
      } catch (IOException | IllegalArgumentException failure) {
        output.accept("Cannot play sound '" + name + "': " + failure.getMessage() + "\n");
      }
    }
  }

  @Override
  public void close() {
    if (ownsPlay) {
      commands.unregister("play");
      ownsPlay = false;
    }
  }
}
