package dev.bluevista.craftq3.engine.net.msg;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.delta.UserCommandDeltaCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

/** Protocol-68 client payloads before legacy XOR and netchan framing. */
public final class ClientMessageCodec {
  private ClientMessageCodec() {}

  public record Command(int sequence, String text) {
    public Command {
      if (text.length() >= Protocol68.MAX_STRING_CHARS
          || text.chars().anyMatch(c -> c == 0 || c > 255))
        throw new IllegalArgumentException("Invalid reliable client command byte string");
    }
  }

  public record Movement(boolean requestDelta, List<byte[]> commands) {
    public Movement {
      if (commands.isEmpty() || commands.size() > Protocol68.MAX_PACKET_USERCMDS)
        throw new IllegalArgumentException("Movement must contain 1..32 commands");
      commands =
          commands.stream()
              .map(
                  command -> {
                    if (command.length != Protocol68.USERCMD_BYTES)
                      throw new IllegalArgumentException("Invalid canonical user command size");
                    return command.clone();
                  })
              .toList();
    }

    @Override
    public List<byte[]> commands() {
      return commands.stream().map(byte[]::clone).toList();
    }
  }

  /** Null movement is a reliable-only/keepalive payload. Sequence policy belongs to the session. */
  public record Message(
      int serverId,
      int messageAcknowledge,
      int serverCommandAcknowledge,
      List<Command> commands,
      Movement movement) {
    public Message {
      if (commands.size() > Protocol68.MAX_RELIABLE_COMMANDS)
        throw new IllegalArgumentException("Too many reliable client commands");
      commands = List.copyOf(commands);
    }
  }

  public static void write(
      MessageWriter writer, Message message, int checksumFeed, String serverCommand) {
    writer.transaction(
        output -> {
          writeBody(output, message, checksumFeed, serverCommand);
          output.byteValue(Protocol68.CLC_EOF);
          return null;
        });
  }

  /** The native CL_WritePacket boundary, before its netchan wrapper appends Protocol68.CLC_EOF. */
  public static void writeBody(
      MessageWriter writer, Message message, int checksumFeed, String serverCommand) {
    writer.transaction(
        output -> {
          output.intValue(message.serverId);
          output.intValue(message.messageAcknowledge);
          output.intValue(message.serverCommandAcknowledge);
          for (var command : message.commands) {
            output.byteValue(Protocol68.CLC_CLIENTCOMMAND);
            output.intValue(command.sequence);
            output.stringValue(command.text);
          }
          if (message.movement != null) {
            int key =
                MessageHash.userCommandKey(checksumFeed, message.messageAcknowledge, serverCommand);
            output.byteValue(
                message.movement.requestDelta ? Protocol68.CLC_MOVE : Protocol68.CLC_MOVENODELTA);
            output.byteValue(message.movement.commands.size());
            byte[] previous = null;
            for (byte[] command : message.movement.commands) {
              UserCommandDeltaCodec.write(output, key, previous, command);
              previous = command;
            }
          }
          return null;
        });
  }

  /** A history callback supplies the exact acknowledged server command for keyed movement. */
  public static Message read(
      MessageReader reader, int checksumFeed, IntFunction<String> serverCommands) {
    Objects.requireNonNull(serverCommands);
    return reader.transaction(
        input -> {
          int server = input.intValue(),
              acknowledge = input.intValue(),
              commandAck = input.intValue();
          var commands = new ArrayList<Command>();
          Movement movement = null;
          int operations = 0;
          while (true) {
            int code = input.byteValue();
            if (code == Protocol68.CLC_EOF)
              return new Message(server, acknowledge, commandAck, commands, movement);
            if (++operations > Protocol68.MAX_CLIENT_OPERATIONS)
              throw new IllegalArgumentException("Too many client operations");
            if (code == Protocol68.CLC_NOP && movement == null) continue;
            if (movement != null)
              throw new IllegalArgumentException("Operations follow terminal client movement");
            if (code == Protocol68.CLC_CLIENTCOMMAND) {
              if (commands.size() == Protocol68.MAX_RELIABLE_COMMANDS)
                throw new IllegalArgumentException("Too many reliable client commands");
              commands.add(new Command(input.intValue(), input.stringValue()));
            } else if (code == Protocol68.CLC_MOVE || code == Protocol68.CLC_MOVENODELTA) {
              int count = input.byteValue();
              if (count < 1 || count > Protocol68.MAX_PACKET_USERCMDS)
                throw new IllegalArgumentException("Invalid client movement command count");
              String serverCommand = serverCommands.apply(commandAck);
              if (serverCommand == null)
                throw new IllegalArgumentException("Missing acknowledged reliable server command");
              int key = MessageHash.userCommandKey(checksumFeed, acknowledge, serverCommand);
              var userCommands = new ArrayList<byte[]>();
              byte[] previous = null;
              for (int i = 0; i < count; i++) {
                previous = UserCommandDeltaCodec.read(input, key, previous);
                userCommands.add(previous);
              }
              movement = new Movement(code == Protocol68.CLC_MOVE, userCommands);
            } else
              throw new IllegalArgumentException(
                  "Unsupported protocol-68 client operation " + code);
          }
        });
  }
}
