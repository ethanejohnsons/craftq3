package dev.bluevista.craftq3.engine.benchmarks;

import static dev.bluevista.craftq3.engine.vm.Opcode.*;

import dev.bluevista.craftq3.engine.common.time.Clock;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.Syscalls;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * QVM execution speed on synthetic hot loops shaped like lcc output: locals, loads and stores,
 * integer arithmetic and a procedure call, a syscall or global array traffic per iteration. One
 * operation is one {@code vmMain} running {@link #ITERATIONS} loop iterations; the {@code
 * instructions} counter reports QVM instructions per second.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class VmBenchmark {
  static final int ITERATIONS = 10_000;

  /** Base address of the 1024-word global array the memory loop works on. */
  private static final int ARRAY = 256;

  /** The execution engine; a JIT-compiled engine adds a value here. */
  @Param({"interpreter"})
  public String engine;

  private QvmInstance calls;
  private QvmInstance syscalls;
  private QvmInstance memory;
  private long callInstructions;
  private long syscallInstructions;
  private long memoryInstructions;

  /** QVM instructions retired, reported by JMH as a rate. */
  @State(Scope.Thread)
  @AuxCounters(AuxCounters.Type.OPERATIONS)
  public static class Retired {
    public long instructions;

    @Setup(Level.Iteration)
    public void clear() {
      instructions = 0;
    }
  }

  @Setup
  public void setUp() {
    if (!engine.equals("interpreter"))
      throw new IllegalArgumentException("Unknown VM engine " + engine);
    calls = instance(hotLoop(false), null);
    syscalls = instance(hotLoop(true), (vm, number, args) -> (args[0] >> 1) + args[0]);
    memory = instance(memoryLoop(), null);
    callInstructions = verify(calls, hotLoopExpected(ITERATIONS));
    syscallInstructions = verify(syscalls, hotLoopExpected(ITERATIONS));
    memoryInstructions = verify(memory, memoryLoopExpected(ITERATIONS));
  }

  @Benchmark
  public int procedureCalls(Retired retired) {
    retired.instructions += callInstructions;
    return calls.vmMain(ITERATIONS);
  }

  @Benchmark
  public int syscalls(Retired retired) {
    retired.instructions += syscallInstructions;
    return syscalls.vmMain(ITERATIONS);
  }

  @Benchmark
  public int memoryLoadsStores(Retired retired) {
    retired.instructions += memoryInstructions;
    return memory.vmMain(ITERATIONS);
  }

  private static QvmInstance instance(SyntheticQvm code, Syscalls handler) {
    var limits = new QvmInstance.Limits(Long.MAX_VALUE / 8, Long.MAX_VALUE / 8, 0, 4096, 1024, 16);
    Syscalls none =
        (vm, number, args) -> {
          throw new IllegalStateException("Unexpected syscall " + number);
        };
    return new QvmInstance(
        code.image("synthetic"), handler == null ? none : handler, limits, Clock.system());
  }

  /** Runs one call on a fresh instance, checks its result and returns its instruction count. */
  private static long verify(QvmInstance vm, int expected) {
    long before = vm.stats().instructions();
    int result = vm.vmMain(ITERATIONS);
    if (result != expected)
      throw new IllegalStateException("Synthetic QVM returned " + result + ", not " + expected);
    return vm.stats().instructions() - before;
  }

  /**
   * vmMain(n): {@code sum = 0; for (i = 0; i < n; i++) sum = (sum + 3 * i) ^ f(i); return sum;}
   * where {@code f(x) = (x >> 1) + x} is a procedure, or syscall 0 when {@code syscall} is set.
   */
  static SyntheticQvm hotLoop(boolean syscall) {
    var code =
        new SyntheticQvm()
            .op(ENTER, 32)
            .op(LOCAL, 16)
            .op(CONST, 0)
            .op(STORE4)
            .op(LOCAL, 20)
            .op(CONST, 0)
            .op(STORE4)
            .op(LOCAL, 16) // 7: loop head
            .op(LOAD4)
            .op(LOCAL, 40)
            .op(LOAD4)
            .op(GEI, 35)
            .op(LOCAL, 20)
            .op(LOCAL, 20)
            .op(LOAD4)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(CONST, 3)
            .op(MULI)
            .op(ADD)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(ARG, 8)
            .op(CONST, syscall ? -1 : 38)
            .op(CALL)
            .op(BXOR)
            .op(STORE4)
            .op(LOCAL, 16)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(CONST, 1)
            .op(ADD)
            .op(STORE4)
            .op(CONST, 7)
            .op(JUMP)
            .op(LOCAL, 20) // 35: exit
            .op(LOAD4)
            .op(LEAVE, 32)
            .op(ENTER, 16) // 38: f
            .op(LOCAL, 24)
            .op(LOAD4)
            .op(CONST, 1)
            .op(RSHI)
            .op(LOCAL, 24)
            .op(LOAD4)
            .op(ADD)
            .op(LEAVE, 16);
    if (code.size() != 47) throw new IllegalStateException("hot loop layout changed");
    return code;
  }

  static int hotLoopExpected(int n) {
    int sum = 0;
    for (int i = 0; i < n; i++) sum = (sum + 3 * i) ^ ((i >> 1) + i);
    return sum;
  }

  /**
   * vmMain(n) over a global {@code int a[1024]}: {@code for (i = 0; i < n; i++) { a[i & 1023] =
   * a[(i * 7) & 1023] + i; sum += ((byte *) a)[i & 4095]; } return sum;}
   */
  static SyntheticQvm memoryLoop() {
    var code =
        new SyntheticQvm()
            .bss(ARRAY + 4096 + 0x10000)
            .op(ENTER, 32)
            .op(LOCAL, 16)
            .op(CONST, 0)
            .op(STORE4)
            .op(LOCAL, 20)
            .op(CONST, 0)
            .op(STORE4)
            .op(LOCAL, 16) // 7: loop head
            .op(LOAD4)
            .op(LOCAL, 40)
            .op(LOAD4)
            .op(GEI, 55)
            .op(CONST, ARRAY) // &a[i & 1023]
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(CONST, 1023)
            .op(BAND)
            .op(CONST, 2)
            .op(LSH)
            .op(ADD)
            .op(CONST, ARRAY) // a[(i * 7) & 1023]
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(CONST, 7)
            .op(MULI)
            .op(CONST, 1023)
            .op(BAND)
            .op(CONST, 2)
            .op(LSH)
            .op(ADD)
            .op(LOAD4)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(ADD)
            .op(STORE4)
            .op(LOCAL, 20) // 35: sum += ((byte *) a)[i & 4095]
            .op(LOCAL, 20)
            .op(LOAD4)
            .op(CONST, ARRAY)
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(CONST, 4095)
            .op(BAND)
            .op(ADD)
            .op(LOAD1)
            .op(ADD)
            .op(STORE4)
            .op(LOCAL, 16) // 47: i++
            .op(LOCAL, 16)
            .op(LOAD4)
            .op(CONST, 1)
            .op(ADD)
            .op(STORE4)
            .op(CONST, 7)
            .op(JUMP)
            .op(LOCAL, 20) // 55: exit
            .op(LOAD4)
            .op(LEAVE, 32);
    if (code.size() != 58) throw new IllegalStateException("memory loop layout changed");
    return code;
  }

  /** The memory loop's result on a fresh instance (zeroed array). */
  static int memoryLoopExpected(int n) {
    int[] a = new int[1024];
    int sum = 0;
    for (int i = 0; i < n; i++) {
      a[i & 1023] = a[(i * 7) & 1023] + i;
      int offset = i & 4095;
      sum += (a[offset >> 2] >>> ((offset & 3) * 8)) & 255;
    }
    return sum;
  }
}
