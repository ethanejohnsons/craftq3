package dev.bluevista.craftq3.engine.benchmarks;

import dev.bluevista.craftq3.engine.net.Protocol68;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Baselines;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec.Snapshot;
import dev.bluevista.craftq3.engine.net.msg.MessageReader;
import dev.bluevista.craftq3.engine.net.msg.MessageWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Protocol-68 snapshot coding: a delta-compressed 64-entity snapshot (8 moving players, 8 missiles,
 * 46 static items and movers, two entities removed and two added) encoded into and decoded from a
 * Huffman message, and the raw fixed-Huffman byte throughput of the message layer (one operation is
 * one payload byte).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class NetBenchmark {
  static final int ENTITIES = 64;
  static final int PAYLOAD_BYTES = 8192;

  private Snapshot previous;
  private Snapshot current;
  private Baselines baselines;
  private final MessageWriter writer = new MessageWriter();
  private final MessageReader reader = new MessageReader();
  private byte[] encoded;
  private byte[] payload;
  private byte[] huffman;

  @Setup
  public void setUp() {
    Random random = new Random(68);
    List<byte[]> before = new ArrayList<>(), after = new ArrayList<>(), base = new ArrayList<>();
    for (int i = 0; i < ENTITIES; i++) {
      // Players 0-7, missiles 64-71, items and movers from 100; 140/141 removed, 200/201 added.
      int number = i < 8 ? i : i < 16 ? 56 + i : 84 + i;
      byte[] state = entity(number, random);
      base.add(state.clone());
      before.add(state);
    }
    for (byte[] old : before) {
      int number = word(old, 0);
      if (number == 140 || number == 141) continue;
      byte[] state = old.clone();
      if (number < 8) movePlayer(state, random);
      else if (number < 72) moveMissile(state, random);
      after.add(state);
    }
    after.add(entity(200, random));
    after.add(entity(201, random));
    after.sort((a, b) -> Integer.compare(word(a, 0), word(b, 0)));
    baselines = new Baselines(base);
    byte[] area = {(byte) 0xff, 0x0f};
    byte[] player = new byte[Protocol68.PLAYER_STATE_BYTES];
    previous = new Snapshot(100, 50_000, 0, area, player, before);
    putFloat(player, 20, 512.25f);
    putFloat(player, 24, -96.5f);
    putFloat(player, 32, 320f);
    putFloat(player, 152, 4.5f);
    putFloat(player, 156, 91.25f);
    putWord(player, 0, 50_045);
    current = new Snapshot(101, 50_050, 0, area, player, after);
    writer.reset();
    SnapshotDeltaCodec.write(writer, previous, current, baselines);
    encoded = writer.bytes();
    System.out.println("Delta snapshot: " + encoded.length + " bytes");
    Snapshot decoded = decode(reader.reset(encoded, 0, encoded.length));
    if (decoded.entities().size() != after.size())
      throw new IllegalStateException("Snapshot round trip lost entities");

    // Payload bytes skewed toward small values, like delta-coded fields and strings.
    payload = new byte[PAYLOAD_BYTES];
    for (int i = 0; i < payload.length; i++)
      payload[i] =
          (byte) (i % 7 == 0 ? 'a' + random.nextInt(26) : Math.abs(random.nextGaussian() * 12));
    writer.reset();
    for (byte b : payload) writer.byteValue(b);
    huffman = writer.bytes();
  }

  @Benchmark
  public int encodeDeltaSnapshot() {
    writer.reset();
    SnapshotDeltaCodec.write(writer, previous, current, baselines);
    return writer.bitPosition();
  }

  @Benchmark
  public Snapshot decodeDeltaSnapshot() {
    return decode(reader.reset(encoded, 0, encoded.length));
  }

  @Benchmark
  @OperationsPerInvocation(PAYLOAD_BYTES)
  public int huffmanEncode() {
    writer.reset();
    for (byte b : payload) writer.byteValue(b);
    return writer.bitPosition();
  }

  @Benchmark
  @OperationsPerInvocation(PAYLOAD_BYTES)
  public void huffmanDecode(Blackhole sink) {
    reader.reset(huffman, 0, huffman.length);
    int sum = 0;
    for (int i = 0; i < PAYLOAD_BYTES; i++) sum += reader.byteValue();
    sink.consume(sum);
  }

  private Snapshot decode(MessageReader input) {
    return SnapshotDeltaCodec.read(input, 101, baselines, sequence -> previous);
  }

  private static byte[] entity(int number, Random random) {
    byte[] state = new byte[Protocol68.ENTITY_STATE_BYTES];
    putWord(state, 0, number);
    putWord(state, 4, number < 8 ? 1 : number < 72 ? 3 : 2); // eType: player, missile, item
    putWord(state, 12, number < 72 ? 2 : 0); // pos.trType
    for (int axis = 0; axis < 3; axis++) {
      float value = (random.nextFloat() - 0.5f) * 4096;
      putFloat(state, 24 + 4 * axis, value); // pos.trBase
      putFloat(state, 92 + 4 * axis, value); // origin
    }
    putWord(state, 160, 1 + random.nextInt(40)); // modelindex
    putWord(state, 148, number < 8 ? 1022 : 1023); // groundEntityNum
    putWord(state, 176, number < 8 ? 0x3f0f0f : 0); // solid
    return state;
  }

  private static void movePlayer(byte[] state, Random random) {
    for (int axis = 0; axis < 2; axis++) {
      int offset = 24 + 4 * axis;
      putFloat(state, offset, getFloat(state, offset) + random.nextFloat() * 16);
      putFloat(state, 36 + 4 * axis, random.nextFloat() * 320); // pos.trDelta
    }
    putFloat(state, 64, random.nextFloat() * 360); // apos.trBase[1]
    putWord(state, 196, random.nextInt(64)); // legsAnim
    putWord(state, 16, 50_050); // pos.trTime
  }

  private static void moveMissile(byte[] state, Random random) {
    putWord(state, 16, 50_000 + random.nextInt(50)); // pos.trTime
    putFloat(state, 36, 900);
  }

  private static int word(byte[] state, int offset) {
    return ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
  }

  private static float getFloat(byte[] state, int offset) {
    return ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getFloat(offset);
  }

  private static void putWord(byte[] state, int offset, int value) {
    ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
  }

  private static void putFloat(byte[] state, int offset, float value) {
    ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, value);
  }
}
