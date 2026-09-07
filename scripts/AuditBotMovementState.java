import dev.bluevista.craftq3.botlib.movement.BotMovement;
import dev.bluevista.craftq3.botlib.movement.MovementInit;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Authored operation sequences compared with unchanged native movement-state exports. */
class AuditBotMovementState {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("AuditBotMovementState <native oracle>");
    var process = new ProcessBuilder(args[0]).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    int count = 20000;
    var random = new Random(72);
    var zero = new Vec3(0, 0, 0);
    float time = 0;
    try (var movement = new BotMovement(message -> { throw new AssertionError(message); });
        var input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        var output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      int handle = movement.allocate();
      for (int query = 0; query < count; query++) {
        String command;
        int operation = random.nextInt(20);
        if (operation < 11) {
          int reach = random.nextInt(5) + 1;
          float duration = random.nextInt(10) - 1;
          movement.recordReachAttempt(handle, reach, duration, time);
          command = "add " + reach + " " + duration;
        } else if (operation < 13) {
          movement.resetAvoidReach(handle);
          command = "reset";
        } else if (operation < 15) {
          movement.resetLastAvoidReach(handle);
          command = "last";
        } else if (operation < 18) {
          float expiry = movement.snapshot(handle).orElseThrow().reachAvoidance().expiresAt();
          time = operation == 17 && expiry >= time ? expiry : time + random.nextInt(8) * .25f;
          command = "clock " + time;
        } else {
          int flags = random.nextInt();
          int previous = movement.snapshot(handle).orElseThrow().movementFlags();
          movement.initialize(handle, new MovementInit(zero, zero, zero, 2, 1, .1f, 2, zero, flags));
          command = "initflags " + previous + " " + flags;
        }
        input.write(command);
        input.newLine();
        input.flush();
        String line = output.readLine();
        if (line == null) throw new AssertionError("Native observer exited at query " + query);
        var fields = line.split(" ");
        var state = movement.snapshot(handle).orElseThrow();
        var avoidance = state.reachAvoidance();
        if (fields.length != 5 || !fields[0].equals("STATE")
            || Integer.parseInt(fields[1]) != avoidance.reachability()
            || Float.floatToIntBits(Float.parseFloat(fields[2])) != Float.floatToIntBits(avoidance.expiresAt())
            || Integer.parseInt(fields[3]) != avoidance.tries()
            || Integer.parseInt(fields[4]) != state.movementFlags())
          throw new AssertionError("Query " + query + " " + command + ": native=" + line + " Java=" + state);
      }
      input.close();
      if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0)
        throw new AssertionError("Native observer did not exit cleanly");
    } finally {
      process.destroyForcibly();
    }
    System.out.println("Movement state PASS: " + count + " operations, exact reach/expiry/tries/flags.");
  }
}
