import dev.bluevista.craftq3.botlib.ea.BotInput;
import dev.bluevista.craftq3.botlib.ea.ElementaryActions;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Differential audit against the separately built native EA oracle; not part of the mod runtime. */
class AuditElementaryActions {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: AuditElementaryActions.java /path/to/ea-oracle");
    Process oracle = new ProcessBuilder(Path.of(args[0]).toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    try (var actions = new ElementaryActions(4, (client, text) -> {});
        var commands = new PrintWriter(new OutputStreamWriter(oracle.getOutputStream(), StandardCharsets.US_ASCII));
        var output = oracle.getInputStream()) {
      Random random = new Random(0x4351334541L);
      int count = 10_000;
      for (int step = 0; step < count; step++) {
        int operation = random.nextInt(8), client = random.nextInt(4);
        commands.print(operation + " " + client);
        switch (operation) {
          case 0 -> { int flags = random.nextInt(); commands.print(" " + flags); actions.action(client, flags); }
          case 1 -> actions.jump(client);
          case 2 -> actions.delayedJump(client);
          case 3 -> actions.resetInput(client);
          case 4 -> {
            Vec3 direction = vector(random, 8);
            float speed = random.nextFloat(-1000, 1000);
            writeVector(commands, direction); commands.print(" " + speed); actions.move(client, direction, speed);
          }
          case 5 -> { Vec3 view = vector(random, 4000); writeVector(commands, view); actions.view(client, view); }
          case 6 -> { int weapon = random.nextInt(); commands.print(" " + weapon); actions.selectWeapon(client, weapon); }
          case 7 -> { float time = random.nextFloat(0, 2); commands.print(" " + time); actions.endRegular(client, time); }
          default -> throw new AssertionError();
        }
        commands.println();
        float time = random.nextFloat(0, 2);
        commands.println("8 " + client + " " + time); commands.flush();
        byte[] expected = output.readNBytes(BotInput.BYTE_SIZE);
        if (expected.length != BotInput.BYTE_SIZE) throw new IllegalStateException("Native oracle ended early at " + step);
        ByteBuffer actual = ByteBuffer.allocate(BotInput.BYTE_SIZE);
        actions.getInput(client, time).writeTo(actual, 0);
        if (!Arrays.equals(expected, actual.array()))
          throw new IllegalStateException("EA ABI mismatch at step=" + step + " operation=" + operation + " client=" + client);
      }
      commands.close();
      if (!oracle.waitFor(5, TimeUnit.SECONDS) || oracle.exitValue() != 0) throw new IllegalStateException("Native oracle failed to finish");
      System.out.println("PASS EA native differential: " + count + " seeded operations and exact 40-byte input comparisons across 4 clients");
    } finally {
      if (oracle.isAlive()) oracle.destroyForcibly();
    }
  }

  private static Vec3 vector(Random random, float extent) {
    return new Vec3(random.nextFloat(-extent, extent), random.nextFloat(-extent, extent), random.nextFloat(-extent, extent));
  }
  private static void writeVector(PrintWriter out, Vec3 vector) {
    out.print(" " + (float) vector.x() + " " + (float) vector.y() + " " + (float) vector.z());
  }
}
