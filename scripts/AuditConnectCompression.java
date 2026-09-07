import dev.bluevista.craftq3.core.net.ConnectPacketCodec;
import dev.bluevista.craftq3.core.net.ConnectionlessPacket;
import dev.bluevista.craftq3.core.net.Protocol68Channel;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/** Authored full-packet native differential. Only its capture stub handles outgoing packets. */
public final class AuditConnectCompression {
  private static final HexFormat HEX = HexFormat.of();

  public static void main(String[] args) throws Exception {
    if (args.length != 1)
      throw new IllegalArgumentException("Usage: AuditConnectCompression <oracle>");
    Random random = new Random(680068);
    int samples = Integer.getInteger("craftq3.audit.connectSamples", 12000);
    if (samples < 1 || samples > 100000) throw new IllegalArgumentException("Invalid sample count");
    int accepted = 0;
    int exact = 0;
    int padding = 0;
    int rejected = 0;
    int truncatedNative = 0;
    int changedNative = 0;
    int nativeDecodes = 0;
    try (Oracle oracle = new Oracle(args[0])) {
      for (int sample = 0; sample < samples; sample++) {
        byte[] suffix = fixture(random, sample);
        byte[] raw = raw(suffix);
        byte[] nativePacket =
            HEX.parseHex(
                oracle.ask("data " + HEX.formatHex(Arrays.copyOfRange(raw, 4, raw.length)))
                    .split(" ")[1]);
        byte[] encoded;
        try {
          encoded = ConnectPacketCodec.compress(raw);
        } catch (IllegalArgumentException overflow) {
          rejected++;
          if (!overflow.getMessage().contains("transmit bound")) throw overflow;
          try {
            byte[] actual = ConnectPacketCodec.decompress(nativePacket);
            if (Arrays.equals(raw, actual))
              throw new AssertionError(
                  "Rejected valid native packet at " + sample + ": " + HEX.formatHex(raw));
            changedNative++;
          } catch (IllegalArgumentException truncated) {
            if (!truncated.getMessage().contains("Truncated")) throw truncated;
            truncatedNative++;
          }
          continue;
        }
        String[] nativeDecoded =
            oracle.ask("decompress 12 16384 " + HEX.formatHex(encoded)).split(" ");
        nativeDecodes++;
        byte[] decoded = HEX.parseHex(nativeDecoded[4]);
        int cursor = Integer.parseInt(nativeDecoded[6]);
        require(Arrays.equals(raw, decoded), "Native decode", sample, raw, encoded);
        require(
            Arrays.equals(raw, ConnectPacketCodec.decompress(encoded)),
            "Java roundtrip",
            sample,
            raw,
            encoded);
        require(
            Arrays.equals(raw, ConnectPacketCodec.decompress(nativePacket)),
            "Native packet decode",
            sample,
            raw,
            nativePacket);
        accepted++;
        if (Arrays.equals(encoded, nativePacket)) {
          exact++;
        } else {
          boolean unusedByte =
              suffix.length > 0
                  && cursor % 8 == 0
                  && encoded.length == nativePacket.length
                  && encoded.length == 12 + cursor / 8 + 1
                  && encoded[encoded.length - 1] == 0;
          if (unusedByte)
            for (int index = 0; index < encoded.length - 1; index++)
              if (encoded[index] != nativePacket[index]) unusedByte = false;
          require(unusedByte, "Meaningful packet bytes", sample, raw, nativePacket);
          padding++;
        }
      }
    }
    System.out.printf(
        "PASS samples=%d accepted=%d exactFullPackets=%d ignoredPaddingOnly=%d nativeDecodes=%d "
            + "rejectedNativeOverflow=%d truncatedNativePackets=%d changedNativePayloads=%d%n",
        samples, accepted, exact, padding, nativeDecodes, rejected, truncatedNative, changedNative);
  }

  private static byte[] fixture(Random random, int sample) {
    return switch (sample % 6) {
      case 0, 1, 5 -> {
        StringBuilder name = new StringBuilder();
        int length = random.nextInt(sample % 6 == 5 ? 220 : 60);
        for (int index = 0; index < length; index++) {
          String alphabet = sample % 6 == 1 ? "AaBb019 ^%\u00e9\u00ff" : "AaBbCc019 ^";
          name.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        List<String> fields =
            new ArrayList<>(
                List.of(
                    "\\name\\" + name,
                    "\\rate\\25000",
                    "\\snaps\\20",
                    "\\model\\sarge",
                    "\\headmodel\\sarge",
                    "\\protocol\\68",
                    "\\qport\\" + random.nextInt(65536),
                    "\\challenge\\" + random.nextInt()));
        Collections.shuffle(fields, random);
        String text = "\"" + String.join("", fields) + "\"";
        if (sample % 6 == 1) text += random.nextBoolean() ? "\0" : "\n";
        yield text.getBytes(StandardCharsets.ISO_8859_1);
      }
      case 2 -> {
        byte[] bytes = new byte[1024 + random.nextInt(4096)];
        for (int index = 0; index < 512; index++) bytes[index] = (byte) index;
        Arrays.fill(bytes, 512, bytes.length, (byte) random.nextInt(256));
        yield bytes;
      }
      case 3 -> {
        byte[] bytes = new byte[1 + random.nextInt(128)];
        random.nextBytes(bytes);
        yield bytes;
      }
      case 4 -> {
        int[] lengths = {
          0, 1, 2, 3, 4, 7, 8, 16, 32, 255, 256, 1023, 8192, Protocol68Channel.MAX_MESSAGE - 12
        };
        byte[] bytes = new byte[lengths[random.nextInt(lengths.length)]];
        Arrays.fill(bytes, (byte) random.nextInt(256));
        yield bytes;
      }
      default -> throw new AssertionError();
    };
  }

  private static byte[] raw(byte[] suffix) {
    byte[] payload = new byte[8 + suffix.length];
    System.arraycopy("connect ".getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 8);
    System.arraycopy(suffix, 0, payload, 8, suffix.length);
    return ConnectionlessPacket.encode(payload);
  }

  private static void require(
      boolean condition, String operation, int sample, byte[] raw, byte[] packet) {
    if (!condition)
      throw new AssertionError(
          operation
              + " sample="
              + sample
              + " raw="
              + HEX.formatHex(raw)
              + " packet="
              + HEX.formatHex(packet));
  }

  private static final class Oracle implements AutoCloseable {
    private final Process process;
    private final BufferedWriter input;
    private final BufferedReader output;

    Oracle(String executable) throws Exception {
      process =
          new ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      input =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII));
      output =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII));
    }

    String ask(String command) throws Exception {
      input.write(command);
      input.newLine();
      input.flush();
      String result = output.readLine();
      if (result == null) throw new IllegalStateException("Native observer stopped for " + command);
      return result;
    }

    @Override
    public void close() throws Exception {
      input.close();
      output.close();
      if (process.waitFor() != 0) throw new IllegalStateException("Native observer failed");
    }
  }
}
