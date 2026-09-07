import dev.bluevista.craftq3.core.net.MessageReader;
import dev.bluevista.craftq3.core.net.MessageWriter;
import dev.bluevista.craftq3.core.net.delta.EntityDeltaCodec;
import dev.bluevista.craftq3.core.net.delta.PlayerDeltaCodec;
import dev.bluevista.craftq3.core.net.delta.StateFields;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Authored state inputs compared with unchanged native message objects; no game assets required. */
class AuditStateDeltas {
  private static final int[] FLOATS = {0, 0x80000000, 0x3f800000, 0xbf800000, 0xc5800000,
      0x457ff000, 0x45800000, 0xc5800800, 0x3f000000, 0x3f800001, 0x7fc12345, 0x7f800000,
      0xff800000, 1, 0x7f7fffff};
  private static final int[] INTS = {0, 1, -1, 127, 128, 255, 256, 32767, 32768, 65535, 65536,
      Integer.MIN_VALUE, Integer.MAX_VALUE};
  private final BufferedWriter input;
  private final BufferedReader output;
  private int cases, bytes;

  AuditStateDeltas(BufferedWriter input, BufferedReader output) { this.input = input; this.output = output; }

  public static void main(String[] args) throws Exception {
    var executable = Path.of(args.length == 0 ? ".tools/netchan-oracle/state-delta-oracle" : args[0]);
    var process = new ProcessBuilder(executable.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    try (var input = process.outputWriter(StandardCharsets.US_ASCII); var output = process.inputReader(StandardCharsets.US_ASCII)) {
      var audit = new AuditStateDeltas(input, output); audit.run();
      System.out.printf("State delta PASS: %d cases, %d wire bytes; zero encoded/decoded mismatches%n", audit.cases, audit.bytes);
    } finally {
      if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
      require(process.exitValue() == 0, "Native process exit");
    }
  }

  private void run() throws Exception {
    send("fields");
    for (var field : StateFields.ENTITY) require(output.readLine().equals("entity " + field.name() + " " + field.byteOffset() + " " + field.bitWidth()), "Entity field metadata");
    for (var field : StateFields.PLAYER) require(output.readLine().equals("player " + field.name() + " " + field.byteOffset() + " " + field.bitWidth()), "Player field metadata");
    require(output.readLine().equals("fields-end"), "Field table length");
    for (boolean entity : new boolean[]{true, false}) {
      int size = entity ? 208 : 468;
      var fields = entity ? StateFields.ENTITY : StateFields.PLAYER;
      for (var field : fields) {
        for (int value : field.bitWidth() == 0 ? FLOATS : INTS) {
          byte[] from = new byte[size], to = new byte[size];
          word(to, field.byteOffset(), value);
          compare(entity, from, to, true, cases % 8, cases);
          compare(entity, to, from, false, cases % 8, cases);
        }
      }
    }
    var random = new Random(0x68de17a);
    for (int trial = 0; trial < 6000; trial++) {
      boolean entity = (trial & 1) == 0;
      int size = entity ? 208 : 468;
      byte[] from = new byte[size], to;
      random.nextBytes(from);
      if (entity) word(from, 0, random.nextInt(1023));
      to = from.clone();
      for (int offset = entity ? 4 : 0; offset < size; offset += 4) {
        if (random.nextInt(8) < trial % 9) word(to, offset, random.nextInt());
      }
      if (trial % 13 == 0) Arrays.fill(to, (byte) 0);
      if (entity) word(to, 0, get(from, 0));
      if (trial % 29 == 0) from = null;
      if (entity && trial % 47 == 0) to = null;
      compare(entity, from, to, random.nextBoolean(), trial % 8, random.nextInt());
    }
    for (int array = 184; array <= 376; array += 64) for (int slot = 0; slot < 16; slot++) {
      for (int value : INTS) {
        byte[] to = new byte[468]; word(to, array + slot * 4, value);
        compare(false, null, to, false, cases % 8, cases);
      }
    }
  }

  private void compare(boolean entity, byte[] from, byte[] to, boolean force, int prefixBits, int prefix) throws Exception {
    String command = (entity ? "entity" : "player") + " " + hex(from) + " " + hex(to) + " " + (force ? 1 : 0) + " " + prefixBits + " " + Integer.toUnsignedString(prefix);
    send(command);
    var writer = new MessageWriter(); if (prefixBits != 0) writer.bits(prefix, prefixBits);
    boolean emitted = true;
    if (entity) emitted = EntityDeltaCodec.write(writer, from, to, force);
    else PlayerDeltaCodec.write(writer, from, to);
    String expected = "encoded " + writer.bitPosition() + " " + hex(writer.bytes());
    String encoded = output.readLine();
    require(expected.equals(encoded), "Case " + cases + " wire mismatch\nnative=" + encoded + "\njava=" + expected + "\n" + command);
    String decoded = output.readLine();
    var reader = new MessageReader(writer.bytes(), writer.bitPosition());
    if (prefixBits != 0) reader.bits(prefixBits);
    if (entity && !emitted) require(decoded.equals("decoded -"), "Native entity omission");
    else {
      byte[] state; int number = 0;
      if (entity) { var update = EntityDeltaCodec.read(reader, from); state = update.state(); number = update.number(); }
      else state = PlayerDeltaCodec.read(reader, from);
      expected = "decoded " + (entity ? number + " " : "") + reader.bitPosition() + " " + hex(state);
      require(expected.equals(decoded), "Case " + cases + " decoded mismatch\nnative=" + decoded + "\njava=" + expected + "\n" + command);
    }
    require(reader.bitPosition() == writer.bitPosition(), "Unconsumed delta bits");
    cases++; bytes += writer.bytes().length;
  }

  private void send(String command) throws IOException { input.write(command); input.newLine(); input.flush(); }
  private static String hex(byte[] bytes) { return bytes == null || bytes.length == 0 ? "-" : HexFormat.of().formatHex(bytes); }
  private static void word(byte[] data, int offset, int value) { ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value); }
  private static int get(byte[] data, int offset) { return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(offset); }
  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
