package dev.bluevista.craftq3.client;

import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.core.fs.VirtualFileSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.vm.Opcode;
import dev.bluevista.craftq3.vm.QvmInterpreter;
import dev.bluevista.craftq3.vm.QvmMemory;
import dev.bluevista.craftq3.vm.QvmReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Small original bytecode programs and in-memory assets, independent of any game installation. */
final class ClientTestData {
  private ClientTestData() {}

  static final class Program {
    private record Instruction(Opcode opcode, int value) {}

    private final List<Instruction> instructions = new ArrayList<>();
    final byte[] data = new byte[512];

    Program op(Opcode opcode, int value) {
      instructions.add(new Instruction(opcode, value));
      return this;
    }

    Program op(Opcode opcode) {
      return op(opcode, 0);
    }

    int size() {
      return instructions.size();
    }

    void patch(int index, int operand) {
      instructions.set(index, new Instruction(instructions.get(index).opcode(), operand));
    }

    Program call(int syscall, int... args) {
      for (int i = 0; i < args.length; i++) op(Opcode.CONST, args[i]).op(Opcode.ARG, 8 + i * 4);
      return op(Opcode.CONST, -1 - syscall).op(Opcode.CALL).op(Opcode.POP);
    }

    byte[] bytes() {
      int code =
          (instructions.stream().mapToInt(i -> 1 + i.opcode().operandBytes()).sum() + 3) & ~3;
      var out = ByteBuffer.allocate(32 + code + data.length).order(ByteOrder.LITTLE_ENDIAN);
      out.putInt(QvmReader.MAGIC)
          .putInt(instructions.size())
          .putInt(32)
          .putInt(code)
          .putInt(32 + code)
          .putInt(data.length)
          .putInt(0)
          .putInt(131072);
      for (var instruction : instructions) {
        out.put((byte) instruction.opcode().ordinal());
        if (instruction.opcode().operandBytes() == 4) out.putInt(instruction.value());
        else if (instruction.opcode().operandBytes() == 1) out.put((byte) instruction.value());
      }
      out.position(32 + code);
      out.put(data);
      return out.array();
    }
  }

  static QvmMemory memory() throws Exception {
    var program = new Program().op(Opcode.ENTER, 8).op(Opcode.CONST, 0).op(Opcode.LEAVE, 8);
    return new QvmInterpreter(QvmReader.read("client fixture", program.bytes()), (m, s, a) -> 0)
        .memory();
  }

  static byte[] server() {
    return new Program()
        .op(Opcode.ENTER, 64)
        .call(15, 4096, 1, 516, 16384, 468)
        .op(Opcode.CONST, 0)
        .op(Opcode.LEAVE, 64)
        .bytes();
  }

  static byte[] client(ClientAbi abi) {
    var program =
        new Program()
            .op(Opcode.ENTER, 64)
            .call(49, 1000)
            .call(15, 200)
            .call(15, 200)
            .call(30, abi == ClientAbi.RETAIL_1999 ? 123 : 0)
            .call(51, 300, 304)
            .call(56, 3, Float.floatToRawIntBits(.5f));
    program
        .op(Opcode.CONST, 300)
        .op(Opcode.LOAD4)
        .op(Opcode.CVIF)
        .op(Opcode.ARG, 8)
        .op(Opcode.CONST, 0)
        .op(Opcode.ARG, 12)
        .op(Opcode.CONST, 1000 + abi.glconfigWidth())
        .op(Opcode.LOAD4)
        .op(Opcode.CVIF)
        .op(Opcode.ARG, 16);
    int[] remaining = {
      Float.floatToRawIntBits(10), 0, 0, Float.floatToRawIntBits(1), Float.floatToRawIntBits(1), 0
    };
    for (int i = 0; i < remaining.length; i++)
      program.op(Opcode.CONST, remaining[i]).op(Opcode.ARG, 20 + i * 4);
    program
        .op(Opcode.CONST, -47)
        .op(Opcode.CALL)
        .op(Opcode.POP)
        .op(Opcode.CONST, 0)
        .op(Opcode.LEAVE, 64);
    System.arraycopy(
        "probe".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, program.data, 200, 5);
    return program.bytes();
  }

  static final class Files implements VirtualFileSystem {
    final Map<String, byte[]> files;
    boolean closed;

    Files(Map<String, byte[]> files) {
      this.files = Map.copyOf(files);
    }

    public Optional<Origin> which(VirtualPath path) {
      return files.containsKey(path.value())
          ? Optional.of(searchOrder().getFirst())
          : Optional.empty();
    }

    public List<VirtualPath> list(String directory) {
      return files.keySet().stream()
          .filter(s -> s.startsWith(directory + "/"))
          .map(VirtualPath::new)
          .sorted()
          .toList();
    }

    public List<Origin> searchOrder() {
      return List.of(new Origin("fixture", "memory", false));
    }

    public byte[] read(VirtualPath path) throws NoSuchFileException {
      var bytes = files.get(path.value());
      if (bytes == null) throw new NoSuchFileException(path.value());
      return bytes.clone();
    }

    public void close() {
      closed = true;
    }
  }

  static final class Audio implements AudioBackend {
    int sounds, clears;
    long voices;
    boolean closed;
    final Map<Long, dev.bluevista.craftq3.platform.audio.PcmStream> streams =
        new java.util.HashMap<>();

    public long stream(dev.bluevista.craftq3.platform.audio.PcmStream source, float gain) {
      streams.put(++voices, source);
      return voices;
    }

    public int register(String name, PcmSound sound) {
      return ++sounds;
    }

    public long play(Playback playback) {
      return ++voices;
    }

    public void updateEntity(int entity, Vec3 origin) {}

    public void beginFrame() {}

    public void submitLoop(Loop loop) {}

    public void endFrame(Listener listener) {}

    public void clearLoops() {
      clears++;
    }

    public void stop(long voice) {
      var source = streams.remove(voice);
      if (source != null) source.close();
    }

    public void stopAll() {}

    public void volume(float gain) {}

    public Diagnostics diagnostics() {
      return new Diagnostics(true, sounds, 0, 0, 0, voices, 0, "");
    }

    public void close() {
      closed = true;
    }
  }
}
