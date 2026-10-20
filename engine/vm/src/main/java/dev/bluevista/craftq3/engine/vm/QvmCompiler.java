package dev.bluevista.craftq3.engine.vm;

import static java.lang.constant.ConstantDescs.CD_CallSite;
import static java.lang.constant.ConstantDescs.CD_MethodHandles_Lookup;
import static java.lang.constant.ConstantDescs.CD_MethodType;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_byte;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a {@link QvmImage} to JVM bytecode with {@code java.lang.classfile}: every procedure
 * ({@code ENTER} to the next {@code ENTER}) becomes a static method {@code int p<entry>(QvmInstance
 * vm, int ps, int ret, int depth, int base)} in a hidden class. The design follows the QVM
 * instruction semantics the interpreter implements; nothing here is derived from other QVM
 * compilers.
 *
 * <p><b>Operands.</b> The QVM operand stack depth at each instruction of a procedure is static
 * (computed here by data flow); operands live on the JVM operand stack. Constants and frame
 * addresses ({@code CONST}, {@code LOCAL}) are kept symbolic until consumed, which folds constant
 * arithmetic and lets loads and stores whose address is provably inside the data segment skip the
 * mask and the bounds check. All other accesses mask and check exactly as the interpreter does.
 *
 * <p><b>Control flow.</b> Branches and constant jumps are JVM branches; a computed {@code JUMP} (a
 * {@code switch} through a jump table) is a JVM switch over the targets the image declares (version
 * 2) or whose instruction numbers appear in the initialized data (version 1, where the tables
 * live). A version 1 target outside that set is checked like the interpreter and finishes the
 * activation in the interpreter. {@code CALL} to a constant procedure is a direct call (an {@code
 * invokedynamic} bound to a constant method handle across classes), a computed {@code CALL}
 * dispatches through the image's handle table, and negative addresses are syscalls.
 *
 * <p><b>Accounting.</b> Instructions are charged at the same points and in the same amounts as the
 * interpreter (per straight-line run, at every control transfer), so budgets, statistics and fault
 * locations match. A local holds the part of the current run that crossed branch targets.
 *
 * <p><b>Fallback.</b> A procedure whose stack depth is not static, that reads operands below its
 * frame, or whose method would exceed {@link #HUGE_METHOD_BYTES} (the JVM does not JIT-compile
 * larger methods) or fail to verify stays in the interpreter; interpreted and compiled procedures
 * call each other freely.
 */
final class QvmCompiler {
  /** Methods larger than this are not compiled by HotSpot ({@code -XX:HugeMethodLimit}). */
  static final int HUGE_METHOD_BYTES = 8000;

  /** QVM instructions per hidden class, which keeps each class's constant pool small. */
  private static final int MAX_CHUNK_INSTRUCTIONS = 16384;

  /** Deepest static operand stack compiled. */
  private static final int MAX_OPERAND_DEPTH = 256;

  private static final int MAX_LAYOUT_PASSES = 4;

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final String PACKAGE = QvmCompiler.class.getPackageName();
  private static final ClassDesc CD_CLASS = ClassDesc.of(PACKAGE + ".QvmCompiled");
  private static final ClassDesc CD_VM = ClassDesc.of(QvmInstance.class.getName());
  private static final ClassDesc CD_RT = ClassDesc.of(QvmJitRuntime.class.getName());
  private static final ClassDesc CD_CODE = ClassDesc.of(QvmCode.class.getName());
  private static final ClassDesc CD_EXCEPTION = ClassDesc.of(QvmException.class.getName());
  private static final ClassDesc CD_BYTES = CD_byte.arrayType();
  private static final ClassDesc CD_INTS = CD_int.arrayType();

  private static final MethodTypeDesc MTD_PROCEDURE =
      MethodTypeDesc.of(CD_int, CD_VM, CD_int, CD_int, CD_int, CD_int);
  private static final DirectMethodHandleDesc BOOTSTRAP =
      MethodHandleDesc.ofMethod(
          DirectMethodHandleDesc.Kind.STATIC,
          CD_CODE,
          "link",
          MethodTypeDesc.of(
              CD_CallSite, CD_MethodHandles_Lookup, CD_String, CD_MethodType, CD_int));

  private static final MethodTypeDesc MTD_DATA = MethodTypeDesc.of(CD_BYTES);
  private static final MethodTypeDesc MTD_I_II = MethodTypeDesc.of(CD_int, CD_int, CD_int);
  private static final MethodTypeDesc MTD_Z_II = MethodTypeDesc.of(CD_boolean, CD_int, CD_int);
  private static final MethodTypeDesc MTD_I_I = MethodTypeDesc.of(CD_int, CD_int);
  private static final MethodTypeDesc MTD_RD = MethodTypeDesc.of(CD_int, CD_BYTES, CD_int);
  private static final MethodTypeDesc MTD_WR = MethodTypeDesc.of(CD_void, CD_BYTES, CD_int, CD_int);
  private static final MethodTypeDesc MTD_PUT =
      MethodTypeDesc.of(CD_void, CD_int, CD_BYTES, CD_int);
  private static final MethodTypeDesc MTD_LD1 = MethodTypeDesc.of(CD_int, CD_int, CD_BYTES);
  private static final MethodTypeDesc MTD_LD =
      MethodTypeDesc.of(CD_int, CD_int, CD_BYTES, CD_VM, CD_int);
  private static final MethodTypeDesc MTD_ST1 =
      MethodTypeDesc.of(CD_void, CD_int, CD_int, CD_BYTES);
  private static final MethodTypeDesc MTD_ST =
      MethodTypeDesc.of(CD_void, CD_int, CD_int, CD_BYTES, CD_VM, CD_int);
  private static final MethodTypeDesc MTD_DIV =
      MethodTypeDesc.of(CD_int, CD_int, CD_int, CD_VM, CD_int);
  private static final MethodTypeDesc MTD_BLOCK_COPY =
      MethodTypeDesc.of(CD_void, CD_int, CD_int, CD_VM, CD_int, CD_int, CD_int);
  private static final MethodTypeDesc MTD_CHARGE = MethodTypeDesc.of(CD_void, CD_int, CD_int);
  private static final MethodTypeDesc MTD_ENTER =
      MethodTypeDesc.of(CD_int, CD_int, CD_int, CD_int, CD_int, CD_int);
  private static final MethodTypeDesc MTD_CALL =
      MethodTypeDesc.of(CD_int, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int);
  private static final MethodTypeDesc MTD_LEAVE =
      MethodTypeDesc.of(CD_void, CD_int, CD_int, CD_int, CD_int);
  private static final MethodTypeDesc MTD_INTERPRET =
      MethodTypeDesc.of(CD_int, CD_int, CD_int, CD_int, CD_int, CD_int);
  private static final MethodTypeDesc MTD_JUMP =
      MethodTypeDesc.of(CD_int, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int, CD_int);
  private static final MethodTypeDesc MTD_FAULT =
      MethodTypeDesc.of(CD_EXCEPTION, CD_int, CD_int, CD_int);

  private static final MethodHandle INTERPRET;

  static {
    try {
      INTERPRET =
          LOOKUP.findVirtual(
              QvmInstance.class,
              "jitInterpret",
              MethodType.methodType(
                  int.class, int.class, int.class, int.class, int.class, int.class));
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  // Local variable slots of every compiled method.
  private static final int VM = 0, PS = 1, RET = 2, DEPTH = 3, BASE = 4, MEM = 5, RUN = 6;
  private static final int TEMPS = 7;

  private final QvmImage image;
  private final int[] ops;
  private final int[] imm;
  private final byte[] flags;
  private final int count;
  private final int size;
  private final int mask;
  private final int stackBottom;
  private final boolean v2;
  private final long[] dataWords;
  private final int[] entries;

  private QvmCompiler(QvmImage image) {
    this.image = image;
    ops = image.opcodes;
    imm = image.operands;
    flags = image.flags;
    count = image.instructionCount();
    size = image.memorySize();
    mask = size - 1;
    stackBottom = image.stackBottom();
    v2 = image.version() == 2;
    dataWords = v2 ? null : codeAddressesInData(image.initialData, count);
    int procedures = 0;
    for (int pc = 0; pc < count; pc++) if (ops[pc] == Op.ENTER) procedures++;
    entries = new int[procedures];
    for (int pc = 0, i = 0; pc < count; pc++) if (ops[pc] == Op.ENTER) entries[i++] = pc;
  }

  /**
   * Compiles {@code image}. Procedures the compiler does not handle are bound to the interpreter,
   * so the result may compile none of them.
   */
  static QvmCode compile(QvmImage image) {
    long start = System.nanoTime();
    return new QvmCompiler(image).compile(start);
  }

  /** The instruction numbers stored as aligned words in initialized data (v1 jump tables). */
  private static long[] codeAddressesInData(byte[] data, int count) {
    long[] bits = new long[(count + 63) >>> 6];
    ByteBuffer words = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    for (int offset = 0; offset + 4 <= data.length; offset += 4) {
      int word = words.getInt(offset);
      if (word > 0 && word < count) bits[word >>> 6] |= 1L << word;
    }
    return bits;
  }

  // ---------------------------------------------------------------------------------- driver

  private QvmCode compile(long start) {
    int n = entries.length;
    Procedure[] procedures = new Procedure[n];
    for (int i = 0; i < n; i++) {
      int end = i + 1 < n ? entries[i + 1] : count;
      procedures[i] = analyze(entries[i], end);
    }
    boolean[] compiled = new boolean[n];
    for (int i = 0; i < n; i++) compiled[i] = procedures[i].failure == null;

    // Lay out classes, build them and drop procedures whose methods are too large or cannot be
    // generated, until the set is stable (dropping one changes its callers' call sites).
    List<Chunk> chunks = null;
    for (int pass = 0; pass < MAX_LAYOUT_PASSES; pass++) {
      chunks = layout(procedures, compiled);
      boolean changed = false;
      for (Chunk chunk : chunks) changed |= build(chunk, procedures, compiled);
      if (!changed) break;
      if (pass == MAX_LAYOUT_PASSES - 1) chunks = null;
    }
    if (chunks == null) throw new IllegalStateException("Compiled layout did not converge");

    QvmCode.Linkage linkage = new QvmCode.Linkage();
    MethodHandle[] handles = new MethodHandle[n];
    int classes = 0;
    long bytecode = 0;
    for (Chunk chunk : chunks) {
      if (chunk.bytes == null) continue;
      if (define(chunk, linkage, handles)) {
        classes++;
        bytecode += chunk.codeBytes;
        continue;
      }
      // Isolate the procedure the JVM rejected: define each on its own.
      for (int index : chunk.procedures) {
        Chunk single = new Chunk(-1, List.of(index));
        if (generate(single, procedures, compiled, true) && define(single, linkage, handles)) {
          classes++;
          bytecode += single.codeBytes;
        } else {
          compiled[index] = false;
          procedures[index].failure = "rejected by the JVM verifier";
        }
      }
    }

    long[] compiledBits = new long[(count + 63) >>> 6];
    Map<String, Integer> fallbacks = new LinkedHashMap<>();
    int compiledCount = 0;
    for (int i = 0; i < n; i++) {
      if (compiled[i] && handles[i] != null) {
        compiledCount++;
        compiledBits[entries[i] >>> 6] |= 1L << entries[i];
      } else {
        handles[i] = MethodHandles.insertArguments(INTERPRET, 5, entries[i]);
        String reason = procedures[i].failure != null ? procedures[i].failure : "not defined";
        fallbacks.merge(reason, 1, Integer::sum);
      }
    }
    linkage.bind(entries, handles);
    var report =
        new QvmCompileReport(
            image.name(),
            n,
            compiledCount,
            fallbacks,
            classes,
            bytecode,
            System.nanoTime() - start);
    return new QvmCode(entries, handles, compiledBits, report);
  }

  /** A hidden class's worth of procedures. */
  private static final class Chunk {
    final int id;
    final List<Integer> procedures;
    byte[] bytes;
    long codeBytes;

    Chunk(int id, List<Integer> procedures) {
      this.id = id;
      this.procedures = procedures;
    }
  }

  private List<Chunk> layout(Procedure[] procedures, boolean[] compiled) {
    List<Chunk> chunks = new ArrayList<>();
    List<Integer> current = new ArrayList<>();
    int instructions = 0;
    for (int i = 0; i < procedures.length; i++) {
      if (!compiled[i]) continue;
      int length = procedures[i].end - procedures[i].entry;
      if (!current.isEmpty() && instructions + length > MAX_CHUNK_INSTRUCTIONS) {
        chunks.add(new Chunk(chunks.size(), current));
        current = new ArrayList<>();
        instructions = 0;
      }
      current.add(i);
      instructions += length;
    }
    if (!current.isEmpty()) chunks.add(new Chunk(chunks.size(), current));
    for (Chunk chunk : chunks)
      for (int index : chunk.procedures) procedures[index].chunk = new int[] {chunk.id};
    return chunks;
  }

  /**
   * Generates {@code chunk}; procedures whose code cannot be generated or exceeds {@link
   * #HUGE_METHOD_BYTES} are marked interpreted. Returns whether any was.
   */
  private boolean build(Chunk chunk, Procedure[] procedures, boolean[] compiled) {
    if (generate(chunk, procedures, compiled, false)) {
      boolean changed = false;
      ClassModel model = ClassFile.of().parse(chunk.bytes);
      long total = 0;
      for (MethodModel method : model.methods()) {
        int length = method.findAttribute(Attributes.code()).orElseThrow().codeLength();
        total += length;
        int entry = Integer.parseInt(method.methodName().stringValue().substring(1));
        if (length > HUGE_METHOD_BYTES) {
          int index = Arrays.binarySearch(entries, entry);
          compiled[index] = false;
          procedures[index].failure = "method over " + HUGE_METHOD_BYTES + " bytes";
          changed = true;
        }
      }
      chunk.codeBytes = total;
      return changed;
    }
    // Find the procedures that cannot be generated.
    boolean changed = false;
    for (int index : chunk.procedures) {
      Chunk single = new Chunk(chunk.id, List.of(index));
      if (!generate(single, procedures, compiled, false)) {
        compiled[index] = false;
        changed = true;
      }
    }
    return changed || markAll(chunk, procedures, compiled);
  }

  private static boolean markAll(Chunk chunk, Procedure[] procedures, boolean[] compiled) {
    for (int index : chunk.procedures) {
      compiled[index] = false;
      if (procedures[index].failure == null) procedures[index].failure = "code generation failed";
    }
    return true;
  }

  /** Builds {@code chunk}'s class bytes; false (with the procedure's failure set) on error. */
  private boolean generate(
      Chunk chunk, Procedure[] procedures, boolean[] compiled, boolean isolated) {
    if (isolated) procedures[chunk.procedures.getFirst()].chunk = new int[] {-2};
    int self = isolated ? -2 : chunk.id;
    try {
      chunk.bytes =
          ClassFile.of()
              .build(
                  CD_CLASS,
                  cb -> {
                    cb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
                    for (int index : chunk.procedures) {
                      Procedure procedure = procedures[index];
                      cb.withMethodBody(
                          "p" + procedure.entry,
                          MTD_PROCEDURE,
                          ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC,
                          code -> new Emitter(procedure, code, procedures, compiled, self).emit());
                    }
                  });
      if (isolated) {
        ClassModel model = ClassFile.of().parse(chunk.bytes);
        chunk.codeBytes =
            model.methods().getFirst().findAttribute(Attributes.code()).orElseThrow().codeLength();
      }
      return true;
    } catch (RuntimeException failure) {
      chunk.bytes = null;
      if (chunk.procedures.size() == 1) {
        String message = String.valueOf(failure.getMessage());
        procedures[chunk.procedures.getFirst()].failure =
            message.startsWith("Code length")
                ? "method over the JVM's 65535-byte limit"
                : "code generation failed (" + failure.getClass().getSimpleName() + ")";
      }
      return false;
    }
  }

  private boolean define(Chunk chunk, QvmCode.Linkage linkage, MethodHandle[] handles) {
    try {
      MethodHandles.Lookup lookup =
          LOOKUP.defineHiddenClassWithClassData(chunk.bytes, linkage, true);
      MethodHandle[] defined = new MethodHandle[chunk.procedures.size()];
      for (int i = 0; i < defined.length; i++) {
        int entry = entries[chunk.procedures.get(i)];
        defined[i] = lookup.findStatic(lookup.lookupClass(), "p" + entry, QvmCode.PROCEDURE);
      }
      for (int i = 0; i < defined.length; i++) handles[chunk.procedures.get(i)] = defined[i];
      return true;
    } catch (LinkageError | ReflectiveOperationException | IllegalArgumentException failure) {
      return false;
    }
  }

  // -------------------------------------------------------------------------------- analysis

  private static final int JUMP_COMPUTED = 0, JUMP_DIRECT = 1, JUMP_KNOWN = 2;

  /** One procedure's static facts. */
  private static final class Procedure {
    final int entry;
    final int end;
    final int frameSize;

    /** Operand depth before each instruction ({@code pc - entry}); -1 where unreachable. */
    final int[] depth;

    /** Instructions control may reach other than by falling through. */
    final boolean[] label;

    /** Per JUMP: {@link #JUMP_COMPUTED}, {@link #JUMP_DIRECT} or {@link #JUMP_KNOWN}. */
    final byte[] jumpKind;

    /** Targets compiled into each computed JUMP's switch, ascending. */
    int[] cases = new int[0];

    int maxDepth;
    String failure;

    /** The chunk holding the procedure: one element, -1 before layout. */
    int[] chunk = {-1};

    Procedure(int entry, int end, int frameSize) {
      this.entry = entry;
      this.end = end;
      this.frameSize = frameSize;
      depth = new int[end - entry];
      label = new boolean[end - entry];
      jumpKind = new byte[end - entry];
      Arrays.fill(depth, -1);
    }
  }

  /** Thrown inside analysis: the procedure stays interpreted for {@code reason}. */
  private static final class Unsupported extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Unsupported(String reason) {
      super(reason, null, false, false);
    }
  }

  private boolean candidate(int pc) {
    return v2
        ? (flags[pc] & QvmImage.FLAG_JUMP_TABLE_TARGET) != 0
        : (dataWords[pc >>> 6] & 1L << pc) != 0;
  }

  private static boolean branch(int op) {
    return op >= Op.EQ && op <= Op.GEF;
  }

  private Procedure analyze(int entry, int end) {
    Procedure p = new Procedure(entry, end, imm[entry]);
    try {
      new Analysis(p).run();
    } catch (Unsupported reason) {
      p.failure = reason.getMessage();
    } catch (RuntimeException failure) {
      p.failure = "analysis failed (" + failure.getClass().getSimpleName() + ")";
    }
    return p;
  }

  /** Operand-depth data flow over one procedure. */
  private final class Analysis {
    private final Procedure p;
    private final int entry;
    private final int end;
    private int[] depth;
    private int maxDepth;
    private final int[] work;
    private int pending;
    private List<Integer> computed = new ArrayList<>();

    Analysis(Procedure p) {
      this.p = p;
      entry = p.entry;
      end = p.end;
      depth = p.depth;
      work = new int[end - entry];
    }

    void run() {
      boolean[] label = p.label;
      boolean anyComputed = false;
      for (int pc = entry + 1; pc < end; pc++) {
        int op = ops[pc];
        if (branch(op)) label[imm[pc] - entry] = true;
        if (op == Op.JUMP && ops[pc - 1] == Op.CONST) {
          int target = imm[pc - 1];
          if (target > entry && target < end) label[target - entry] = true;
        }
      }
      for (int pc = entry + 1; pc < end; pc++)
        if (ops[pc] == Op.JUMP && (ops[pc - 1] != Op.CONST || label[pc - entry]))
          anyComputed = true;
      List<Integer> candidates = new ArrayList<>();
      // A version 1 data word naming the JUMP of a CONST/JUMP pair is a coincidence (case labels
      // start statements); honouring it would turn the constant jump into a computed one. Should
      // a computed jump land there after all, the interpreter takes over.
      if (anyComputed)
        for (int pc = entry + 1; pc < end; pc++)
          if (candidate(pc)
              && (v2 || ops[pc] != Op.JUMP || ops[pc - 1] != Op.CONST || label[pc - entry])) {
            candidates.add(pc);
            label[pc - entry] = true;
          }
      for (int pc = entry + 1; pc < end; pc++) {
        if (ops[pc] != Op.JUMP) continue;
        boolean constant = ops[pc - 1] == Op.CONST && !label[pc - entry];
        p.jumpKind[pc - entry] =
            (byte)
                (v2 && (flags[pc] & QvmImage.FLAG_DIRECT_JUMP) != 0
                    ? JUMP_DIRECT
                    : constant ? (v2 ? JUMP_KNOWN : JUMP_DIRECT) : JUMP_COMPUTED);
      }

      if (entry + 1 < end) reach(entry + 1, 0);
      flow();

      // Computed jumps may land on any candidate; accept those whose depth agrees.
      List<Integer> cases = new ArrayList<>();
      List<Integer> open = new ArrayList<>(candidates);
      boolean progress = true;
      while (!computed.isEmpty() && progress) {
        progress = false;
        int target = jumpDepth();
        for (var it = open.iterator(); it.hasNext(); ) {
          int c = it.next();
          int known = depth[c - entry];
          if (known != -1) {
            it.remove();
            if (known == target) cases.add(c);
            else if (v2) throw new Unsupported("jump table target with another operand depth");
            continue;
          }
          int[] savedDepth = depth.clone();
          int savedMax = maxDepth;
          List<Integer> savedComputed = new ArrayList<>(computed);
          try {
            reach(c, target);
            flow();
            jumpDepth();
            cases.add(c);
            it.remove();
            progress = true;
          } catch (Unsupported conflict) {
            if (v2) throw conflict;
            System.arraycopy(savedDepth, 0, depth, 0, depth.length);
            maxDepth = savedMax;
            computed = savedComputed;
            pending = 0;
            it.remove();
          }
        }
      }
      cases.sort(null);
      p.cases = cases.stream().mapToInt(Integer::intValue).toArray();
      p.maxDepth = maxDepth;
    }

    /** The operand depth every computed jump leaves at its target (version 1: zero). */
    private int jumpDepth() {
      int target = -1;
      for (int pc : computed) {
        int d = depth[pc - entry] - 1;
        if (target != -1 && d != target)
          throw new Unsupported("computed jumps with different operand depths");
        target = d;
      }
      if (!v2 && target != 0) throw new Unsupported("computed jump with operands below its target");
      return target;
    }

    private void reach(int pc, int d) {
      if (d > MAX_OPERAND_DEPTH) throw new Unsupported("operand depth over " + MAX_OPERAND_DEPTH);
      int known = depth[pc - entry];
      if (known == d) return;
      if (known != -1) throw new Unsupported("operand depth is not static");
      depth[pc - entry] = d;
      if (d > maxDepth) maxDepth = d;
      work[pending++] = pc;
    }

    private void next(int pc, int d) {
      if (pc + 1 < end) reach(pc + 1, d);
    }

    private void need(int d, int operands) {
      if (d < operands) throw new Unsupported("operand underflow");
    }

    private void flow() {
      while (pending > 0) {
        int pc = work[--pending];
        int d = depth[pc - entry];
        int op = ops[pc];
        switch (op) {
          case Op.UNDEF, Op.BREAK, Op.ENTER, Op.LEAVE -> {}
          case Op.IGNORE -> next(pc, d);
          case Op.PUSH, Op.CONST, Op.LOCAL -> next(pc, d + 1);
          case Op.POP -> {
            if (d > 0) next(pc, d - 1);
          }
          case Op.CALL -> {
            need(d, 1);
            next(pc, d);
          }
          case Op.JUMP -> {
            need(d, 1);
            switch (p.jumpKind[pc - entry]) {
              case JUMP_DIRECT -> {
                int target = imm[pc - 1];
                if (target > entry && target < end) reach(target, d - 1);
              }
              case JUMP_KNOWN -> {
                int target = imm[pc - 1];
                if (target > entry
                    && target < end
                    && (flags[target] & QvmImage.FLAG_JUMP_TABLE_TARGET) != 0) reach(target, d - 1);
              }
              default -> computed.add(pc);
            }
          }
          case Op.LOAD1,
              Op.LOAD2,
              Op.LOAD4,
              Op.SEX8,
              Op.SEX16,
              Op.NEGI,
              Op.BCOM,
              Op.NEGF,
              Op.CVIF,
              Op.CVFI -> {
            need(d, 1);
            next(pc, d);
          }
          case Op.STORE1, Op.STORE2, Op.STORE4, Op.BLOCK_COPY -> {
            need(d, 2);
            next(pc, d - 2);
          }
          case Op.ARG -> {
            need(d, 1);
            if (imm[pc] <= p.frameSize - 4) next(pc, d - 1);
          }
          default -> {
            if (branch(op)) {
              need(d, 2);
              next(pc, d - 2);
              reach(imm[pc], d - 2);
            } else {
              need(d, 2);
              next(pc, d - 1);
            }
          }
        }
      }
    }
  }

  // ------------------------------------------------------------------------------ generation

  private static final int MAT = 0, CONST = 1, LOCAL = 2;

  /** Emits one procedure's method. */
  private final class Emitter {
    private final Procedure p;
    private final CodeBuilder cb;
    private final Procedure[] procedures;
    private final boolean[] compiled;
    private final int self;
    private final int entry;
    private final int frameSize;
    private final Label[] labels;
    private final boolean[] fallIn;

    // Symbolic operand stack: MAT entries are on the JVM stack in order.
    private final int[] kind;
    private final int[] value;
    private int n;

    private boolean live = true;

    /** Whether {@link #RUN} holds instructions of the current run before {@link #segStart}. */
    private boolean runDynamic;

    private int segStart;

    Emitter(Procedure p, CodeBuilder cb, Procedure[] procedures, boolean[] compiled, int self) {
      this.p = p;
      this.cb = cb;
      this.procedures = procedures;
      this.compiled = compiled;
      this.self = self;
      entry = p.entry;
      frameSize = p.frameSize;
      labels = new Label[p.end - entry];
      fallIn = new boolean[p.end - entry];
      kind = new int[p.maxDepth + 2];
      value = new int[p.maxDepth + 2];
      for (int pc = entry + 1; pc < p.end; pc++) {
        if (!p.label[pc - entry] || p.depth[pc - entry] < 0) continue;
        labels[pc - entry] = cb.newLabel();
        fallIn[pc - entry] = pc == entry + 1 || fallsThrough(pc - 1);
      }
    }

    private boolean fallsThrough(int pc) {
      int d = p.depth[pc - entry];
      if (d < 0) return false;
      return switch (ops[pc]) {
        case Op.JUMP, Op.LEAVE, Op.UNDEF, Op.BREAK, Op.ENTER -> false;
        case Op.POP -> d > 0;
        default -> true;
      };
    }

    void emit() {
      cb.aload(VM).invokevirtual(CD_VM, "data", MTD_DATA).astore(MEM);
      if (entry == 0) cb.iload(RET).bipush(31).iushr();
      else cb.iconst_0();
      cb.istore(RUN);
      runDynamic = entry == 0;
      segStart = entry + 1;
      // An activation that could overflow the shared operand stack runs in the interpreter, which
      // faults at the exact instruction.
      Label fits = cb.newLabel();
      cb.iload(BASE).loadConstant(p.maxDepth).iadd();
      cb.aload(VM).getfield(CD_VM, "operandStack", CD_INTS).arraylength();
      cb.if_icmple(fits);
      cb.aload(VM).iload(PS).iload(RET).iload(DEPTH).iload(BASE).loadConstant(entry);
      cb.invokevirtual(CD_VM, "jitInterpret", MTD_INTERPRET).ireturn();
      cb.labelBinding(fits);

      for (int pc = entry + 1; pc < p.end; pc++) {
        int d = p.depth[pc - entry];
        if (d < 0) {
          live = false;
          continue;
        }
        Label label = labels[pc - entry];
        if (label != null) {
          if (live) {
            materialize(0, n);
            int k = pc - segStart;
            if (runDynamic) {
              if (k != 0) cb.iinc(RUN, k);
            } else cb.loadConstant(k).istore(RUN);
          }
          cb.labelBinding(label);
          n = d;
          Arrays.fill(kind, 0, n, MAT);
          live = true;
          runDynamic = fallIn[pc - entry];
          segStart = pc;
        } else if (!live) continue;
        instruction(pc);
      }
      if (live) {
        if (p.end < count) fault(QvmInstance.FAULT_FELL_INTO_ENTER, p.end, 0);
        else fault(QvmInstance.FAULT_RAN_PAST_END, count - 1, 0);
      }
    }

    // ---- symbolic stack ----

    private void push(int k, int v) {
      kind[n] = k;
      value[n] = v;
      n++;
    }

    private void pushMat() {
      push(MAT, 0);
    }

    private boolean isConst(int i, int v) {
      return kind[i] == CONST && value[i] == v;
    }

    private void emitValue(int i) {
      if (kind[i] == CONST) cb.loadConstant(value[i]);
      else {
        cb.iload(PS);
        if (value[i] != 0) cb.loadConstant(value[i]).iadd();
      }
    }

    /** Puts every entry in {@code [from, to)} on the JVM stack, keeping JVM order. */
    private void materialize(int from, int to) {
      int lowest = -1;
      for (int i = from; i < to; i++)
        if (kind[i] != MAT) {
          lowest = i;
          break;
        }
      if (lowest < 0) return;
      // Spill the materialized entries above it, then push everything back in order.
      int spilled = 0;
      for (int i = n - 1; i > lowest; i--) if (kind[i] == MAT) cb.istore(TEMPS + spilled++);
      for (int i = lowest; i < n; i++) {
        if (kind[i] == MAT) cb.iload(TEMPS + --spilled);
        else if (i < to) {
          emitValue(i);
          kind[i] = MAT;
        }
      }
    }

    /** Pops symbolic entries (dropping materialized ones from the JVM stack). */
    private void drop(int entries) {
      for (int i = 0; i < entries; i++) if (kind[--n] == MAT) cb.pop();
    }

    // ---- accounting ----

    /** Pushes the instructions of the current run up to and including {@code pc}, plus extra. */
    private void executed(int pc, int extra) {
      int k = pc - segStart + 1 + extra;
      if (runDynamic) {
        cb.iload(RUN);
        if (k != 0) cb.loadConstant(k).iadd();
      } else cb.loadConstant(k);
    }

    private void charge(int pc) {
      cb.aload(VM);
      executed(pc, 0);
      cb.loadConstant(pc).invokevirtual(CD_VM, "jitCharge", MTD_CHARGE);
    }

    /** A taken transfer at {@code pc} to {@code target} (stack fully materialized). */
    private void transfer(int pc, int target) {
      charge(pc);
      gotoTarget(target);
    }

    private void gotoTarget(int target) {
      if (fallIn[target - entry]) cb.iconst_0().istore(RUN);
      cb.goto_(labels[target - entry]);
    }

    /** The interpreter's run restarts after {@code pc}. */
    private void restartRun(int pc) {
      runDynamic = false;
      segStart = pc + 1;
    }

    private void fault(int faultKind, int pc, int faultValue) {
      cb.aload(VM).loadConstant(faultKind).loadConstant(pc).loadConstant(faultValue);
      cb.invokevirtual(CD_VM, "jitFault", MTD_FAULT).athrow();
      live = false;
    }

    private void memoryFault(int pc, int address, int width) {
      cb.aload(VM).loadConstant(pc).loadConstant(address).loadConstant(width);
      cb.invokevirtual(
              CD_VM, "memoryFault", MethodTypeDesc.of(CD_EXCEPTION, CD_int, CD_int, CD_int))
          .athrow();
      live = false;
    }

    // ---- instructions ----

    private void instruction(int pc) {
      int op = ops[pc];
      int operand = imm[pc];
      switch (op) {
        case Op.UNDEF, Op.BREAK -> fault(QvmInstance.FAULT_UNSUPPORTED, pc, 0);
        case Op.IGNORE -> {}
        case Op.ENTER -> fault(QvmInstance.FAULT_FELL_INTO_ENTER, pc, 0);
        case Op.LEAVE -> leave(pc, operand);
        case Op.CALL -> call(pc);
        case Op.PUSH -> push(CONST, 0);
        case Op.POP -> {
          if (n == 0) fault(QvmInstance.FAULT_UNDERFLOW, pc, 0);
          else drop(1);
        }
        case Op.CONST -> push(CONST, operand);
        case Op.LOCAL -> {
          if (operand > frameSize + 8) {
            Label ok = cb.newLabel();
            cb.iload(PS).loadConstant(operand).iadd().loadConstant(size).if_icmple(ok);
            cb.aload(VM).loadConstant(QvmInstance.FAULT_LOCAL).loadConstant(pc).iconst_0();
            cb.invokevirtual(CD_VM, "jitFault", MTD_FAULT).athrow();
            cb.labelBinding(ok);
          }
          push(LOCAL, operand);
        }
        case Op.JUMP -> jump(pc);
        case Op.LOAD1 -> load(pc, 1);
        case Op.LOAD2 -> load(pc, 2);
        case Op.LOAD4 -> load(pc, 4);
        case Op.STORE1 -> store(pc, 1);
        case Op.STORE2 -> store(pc, 2);
        case Op.STORE4 -> store(pc, 4);
        case Op.ARG -> arg(pc, operand);
        case Op.BLOCK_COPY -> {
          materialize(n - 2, n);
          cb.aload(VM).loadConstant(operand);
          executed(pc, 0);
          cb.loadConstant(pc).invokestatic(CD_RT, "blockCopy", MTD_BLOCK_COPY);
          n -= 2;
          restartRun(pc);
        }
        case Op.SEX8, Op.SEX16, Op.NEGI, Op.BCOM, Op.NEGF, Op.CVIF, Op.CVFI -> unary(op);
        case Op.DIVI, Op.DIVU, Op.MODI, Op.MODU -> division(pc, op);
        default -> {
          if (branch(op)) conditional(pc, op, operand);
          else binary(op);
        }
      }
    }

    private void leave(int pc, int operand) {
      if (operand != frameSize) {
        fault(QvmInstance.FAULT_LEAVE_FRAME, pc, 0);
        return;
      }
      if (n != 1) {
        fault(QvmInstance.FAULT_LEAVE_OPERANDS, pc, 0);
        return;
      }
      materialize(0, 1);
      cb.aload(VM).iload(PS).loadConstant(frameSize).iadd().iload(RET);
      executed(pc, 0);
      cb.loadConstant(pc).invokevirtual(CD_VM, "jitLeave", MTD_LEAVE);
      cb.ireturn();
      live = false;
    }

    private void call(int pc) {
      int top = n - 1;
      if (kind[top] == CONST) {
        int target = value[top];
        n--;
        if (target < 0) {
          cb.aload(VM).loadConstant(-1 - target).iload(PS).iload(DEPTH);
          operandTop();
          executed(pc, 0);
          cb.loadConstant(pc).invokevirtual(CD_VM, "jitSyscall", MTD_CALL);
        } else if (target >= count || ops[target] != Op.ENTER) {
          fault(QvmInstance.FAULT_CALL_TARGET, pc, target);
          return;
        } else {
          int index = Arrays.binarySearch(entries, target);
          cb.aload(VM);
          cb.aload(VM).iload(PS).iload(DEPTH).loadConstant(imm[target]);
          executed(pc, 1);
          cb.loadConstant(pc).invokevirtual(CD_VM, "jitEnter", MTD_ENTER);
          cb.loadConstant(pc + 1).iload(DEPTH).iconst_1().iadd();
          operandTop();
          if (!compiled[index]) {
            cb.loadConstant(target).invokevirtual(CD_VM, "jitInterpret", MTD_INTERPRET);
          } else if (procedures[index].chunk[0] == self && self != -2) {
            cb.invokestatic(CD_CLASS, "p" + target, MTD_PROCEDURE);
          } else if (target == entry) {
            cb.invokestatic(CD_CLASS, "p" + target, MTD_PROCEDURE);
          } else {
            cb.invokedynamic(
                DynamicCallSiteDesc.of(BOOTSTRAP, "p" + target, MTD_PROCEDURE, target));
          }
        }
      } else {
        materialize(top, n);
        n--;
        cb.aload(VM).swap().iload(PS).iload(DEPTH);
        operandTop();
        executed(pc, 0);
        cb.loadConstant(pc).invokevirtual(CD_VM, "jitCall", MTD_CALL);
      }
      pushMat();
      restartRun(pc);
    }

    /** Pushes {@code base + n}: the operand stack index above this activation's operands. */
    private void operandTop() {
      cb.iload(BASE);
      if (n != 0) cb.loadConstant(n).iadd();
    }

    private void jump(int pc) {
      int top = n - 1;
      switch (p.jumpKind[pc - entry]) {
        case JUMP_DIRECT, JUMP_KNOWN -> {
          int target = value[top];
          n--;
          if (target <= entry || target >= p.end) {
            fault(QvmInstance.FAULT_JUMP_TARGET, pc, target);
          } else if (p.jumpKind[pc - entry] == JUMP_KNOWN
              && (flags[target] & QvmImage.FLAG_JUMP_TABLE_TARGET) == 0) {
            fault(QvmInstance.FAULT_JUMP_TABLE, pc, target);
          } else {
            materialize(0, n);
            transfer(pc, target);
            live = false;
          }
        }
        default -> computedJump(pc);
      }
    }

    private void computedJump(int pc) {
      materialize(0, n);
      n--;
      int[] cases = p.cases;
      Label miss = cb.newLabel();
      List<SwitchCase> switchCases = new ArrayList<>(cases.length);
      Label[] stubs = new Label[cases.length];
      for (int i = 0; i < cases.length; i++) {
        stubs[i] = cb.newLabel();
        switchCases.add(SwitchCase.of(cases[i], stubs[i]));
      }
      cb.dup();
      if (cases.length > 0
          && (long) cases[cases.length - 1] - cases[0] + 1 <= 2L * cases.length + 8)
        cb.tableswitch(cases[0], cases[cases.length - 1], miss, switchCases);
      else cb.lookupswitch(miss, switchCases);
      for (int i = 0; i < cases.length; i++) {
        cb.labelBinding(stubs[i]);
        cb.pop();
        transfer(pc, cases[i]);
      }
      cb.labelBinding(miss);
      cb.aload(VM).swap().loadConstant(entry).iload(PS).iload(RET).iload(DEPTH).iload(BASE);
      executed(pc, 0);
      cb.loadConstant(pc).invokevirtual(CD_VM, "jitJump", MTD_JUMP).ireturn();
      live = false;
    }

    private void conditional(int pc, int op, int target) {
      int r = n - 1, l = n - 2;
      if (kind[r] == CONST && kind[l] == CONST) {
        boolean taken = taken(op, value[l], value[r]);
        n -= 2;
        if (taken) {
          materialize(0, n);
          transfer(pc, target);
          live = false;
        }
        return;
      }
      materialize(0, n - 1);
      Label skip = cb.newLabel();
      boolean rightConst = kind[r] != MAT;
      if (op <= Op.GEI) {
        if (isConst(r, 0)) {
          switch (op) {
            case Op.EQ -> cb.ifne(skip);
            case Op.NE -> cb.ifeq(skip);
            case Op.LTI -> cb.ifge(skip);
            case Op.LEI -> cb.ifgt(skip);
            case Op.GTI -> cb.ifle(skip);
            default -> cb.iflt(skip);
          }
        } else {
          if (rightConst) emitValue(r);
          switch (op) {
            case Op.EQ -> cb.if_icmpne(skip);
            case Op.NE -> cb.if_icmpeq(skip);
            case Op.LTI -> cb.if_icmpge(skip);
            case Op.LEI -> cb.if_icmpgt(skip);
            case Op.GTI -> cb.if_icmple(skip);
            default -> cb.if_icmplt(skip);
          }
        }
      } else if (op <= Op.GEU) {
        if (rightConst) emitValue(r);
        cb.invokestatic(ClassDesc.of("java.lang.Integer"), "compareUnsigned", MTD_I_II);
        switch (op) {
          case Op.LTU -> cb.ifge(skip);
          case Op.LEU -> cb.ifgt(skip);
          case Op.GTU -> cb.ifle(skip);
          default -> cb.iflt(skip);
        }
      } else {
        if (rightConst) emitValue(r);
        String helper =
            switch (op) {
              case Op.EQF -> "eqf";
              case Op.NEF -> "nef";
              case Op.LTF -> "ltf";
              case Op.LEF -> "lef";
              case Op.GTF -> "gtf";
              default -> "gef";
            };
        cb.invokestatic(CD_RT, helper, MTD_Z_II).ifeq(skip);
      }
      n -= 2;
      transfer(pc, target);
      cb.labelBinding(skip);
    }

    private boolean taken(int op, int l, int r) {
      float fl = Float.intBitsToFloat(l), fr = Float.intBitsToFloat(r);
      return switch (op) {
        case Op.EQ -> l == r;
        case Op.NE -> l != r;
        case Op.LTI -> l < r;
        case Op.LEI -> l <= r;
        case Op.GTI -> l > r;
        case Op.GEI -> l >= r;
        case Op.LTU -> Integer.compareUnsigned(l, r) < 0;
        case Op.LEU -> Integer.compareUnsigned(l, r) <= 0;
        case Op.GTU -> Integer.compareUnsigned(l, r) > 0;
        case Op.GEU -> Integer.compareUnsigned(l, r) >= 0;
        case Op.EQF -> fl == fr;
        case Op.NEF -> fl != fr;
        case Op.LTF -> fl < fr;
        case Op.LEF -> fl <= fr;
        case Op.GTF -> fl > fr;
        default -> fl >= fr;
      };
    }

    /**
     * The in-range address of entry {@code i} when it is statically known to need no mask or check
     * for a {@code width}-byte access; pushes nothing. Returns false otherwise.
     */
    private boolean direct(int i, int width) {
      if (kind[i] == CONST) return value[i] >= 0 && value[i] <= size - width;
      if (kind[i] == LOCAL)
        return value[i] >= -stackBottom && (long) value[i] <= (long) frameSize + 8 - width;
      return false;
    }

    private void load(int pc, int width) {
      int top = n - 1;
      if (kind[top] == CONST && !direct(top, width)) {
        int offset = value[top] & mask;
        if (offset > size - width) {
          memoryFault(pc, offset, width);
          return;
        }
        value[top] = offset;
      }
      if (direct(top, width)) {
        n--;
        cb.aload(MEM);
        emitValue(top);
        cb.invokestatic(CD_RT, width == 1 ? "rd1" : width == 2 ? "rd2" : "rd4", MTD_RD);
      } else {
        materialize(top, n);
        n--;
        cb.aload(MEM);
        if (width == 1) cb.invokestatic(CD_RT, "ld1", MTD_LD1);
        else {
          cb.aload(VM).loadConstant(pc);
          cb.invokestatic(CD_RT, width == 2 ? "ld2" : "ld4", MTD_LD);
        }
      }
      pushMat();
    }

    private void store(int pc, int width) {
      int address = n - 2, data = n - 1;
      if (kind[address] == CONST && !direct(address, width)) {
        int offset = value[address] & mask;
        if (offset > size - width) {
          memoryFault(pc, offset, width);
          return;
        }
        value[address] = offset;
      }
      String suffix = String.valueOf(width);
      if (direct(address, width)) {
        if (kind[data] == MAT) {
          // JVM stack: ..., value
          cb.aload(MEM);
          emitValue(address);
          cb.invokestatic(CD_RT, "put" + suffix, MTD_PUT);
        } else {
          cb.aload(MEM);
          emitValue(address);
          emitValue(data);
          cb.invokestatic(CD_RT, "wr" + suffix, MTD_WR);
        }
        n -= 2;
        return;
      }
      materialize(address, n);
      n -= 2;
      cb.aload(MEM);
      if (width == 1) cb.invokestatic(CD_RT, "st1", MTD_ST1);
      else {
        cb.aload(VM).loadConstant(pc);
        cb.invokestatic(CD_RT, "st" + suffix, MTD_ST);
      }
    }

    private void arg(int pc, int offset) {
      if (offset > frameSize - 4) {
        fault(QvmInstance.FAULT_ARG, pc, 0);
        return;
      }
      int top = n - 1;
      if (kind[top] == MAT) {
        cb.aload(MEM).iload(PS).loadConstant(offset).iadd();
        cb.invokestatic(CD_RT, "put4", MTD_PUT);
      } else {
        cb.aload(MEM).iload(PS).loadConstant(offset).iadd();
        emitValue(top);
        cb.invokestatic(CD_RT, "wr4", MTD_WR);
      }
      n--;
    }

    private void unary(int op) {
      int top = n - 1;
      if (kind[top] == CONST) {
        int v = value[top];
        value[top] =
            switch (op) {
              case Op.SEX8 -> (byte) v;
              case Op.SEX16 -> (short) v;
              case Op.NEGI -> -v;
              case Op.BCOM -> ~v;
              case Op.NEGF -> v ^ 0x80000000;
              case Op.CVIF -> Float.floatToRawIntBits((float) v);
              default -> QvmInstance.truncate(Float.intBitsToFloat(v));
            };
        return;
      }
      materialize(top, n);
      switch (op) {
        case Op.SEX8 -> cb.i2b();
        case Op.SEX16 -> cb.i2s();
        case Op.NEGI -> cb.ineg();
        case Op.BCOM -> cb.iconst_m1().ixor();
        case Op.NEGF -> cb.loadConstant(0x80000000).ixor();
        case Op.CVIF -> cb.invokestatic(CD_RT, "cvif", MTD_I_I);
        default -> cb.invokestatic(CD_RT, "cvfi", MTD_I_I);
      }
    }

    private void division(int pc, int op) {
      int r = n - 1, l = n - 2;
      if (kind[r] == CONST) {
        int divisor = value[r];
        if (divisor == 0) {
          fault(QvmInstance.FAULT_DIVISION, pc, 0);
          return;
        }
        if (kind[l] == CONST) {
          int dividend = value[l];
          n--;
          value[l] =
              switch (op) {
                case Op.DIVI -> dividend / divisor;
                case Op.DIVU -> Integer.divideUnsigned(dividend, divisor);
                case Op.MODI -> dividend % divisor;
                default -> Integer.remainderUnsigned(dividend, divisor);
              };
          kind[l] = CONST;
          return;
        }
        materialize(l, n - 1);
        n--;
        cb.loadConstant(divisor);
        switch (op) {
          case Op.DIVI -> cb.idiv();
          case Op.MODI -> cb.irem();
          case Op.DIVU ->
              cb.invokestatic(ClassDesc.of("java.lang.Integer"), "divideUnsigned", MTD_I_II);
          default ->
              cb.invokestatic(ClassDesc.of("java.lang.Integer"), "remainderUnsigned", MTD_I_II);
        }
        return;
      }
      materialize(l, n);
      n--;
      cb.aload(VM).loadConstant(pc);
      String helper =
          switch (op) {
            case Op.DIVI -> "divi";
            case Op.DIVU -> "divu";
            case Op.MODI -> "modi";
            default -> "modu";
          };
      cb.invokestatic(CD_RT, helper, MTD_DIV);
    }

    private void binary(int op) {
      int r = n - 1, l = n - 2;
      if (kind[r] == CONST && kind[l] == CONST) {
        value[l] = fold(op, value[l], value[r]);
        n--;
        return;
      }
      if (op == Op.ADD && kind[r] == CONST && kind[l] == LOCAL) {
        value[l] += value[r];
        n--;
        return;
      }
      if (op == Op.ADD && kind[l] == CONST && kind[r] == LOCAL) {
        value[l] += value[r];
        kind[l] = LOCAL;
        n--;
        return;
      }
      if (op == Op.SUB && kind[r] == CONST && kind[l] == LOCAL) {
        value[l] -= value[r];
        n--;
        return;
      }
      if (kind[r] != MAT && kind[l] == MAT) {
        emitValue(r);
      } else if (kind[r] == MAT && kind[l] != MAT && commutative(op)) {
        emitValue(l);
      } else materialize(l, n);
      n -= 2;
      switch (op) {
        case Op.ADD -> cb.iadd();
        case Op.SUB -> cb.isub();
        case Op.MULI, Op.MULU -> cb.imul();
        case Op.BAND -> cb.iand();
        case Op.BOR -> cb.ior();
        case Op.BXOR -> cb.ixor();
        case Op.LSH -> cb.ishl();
        case Op.RSHI -> cb.ishr();
        case Op.RSHU -> cb.iushr();
        case Op.ADDF -> cb.invokestatic(CD_RT, "addf", MTD_I_II);
        case Op.SUBF -> cb.invokestatic(CD_RT, "subf", MTD_I_II);
        case Op.MULF -> cb.invokestatic(CD_RT, "mulf", MTD_I_II);
        case Op.DIVF -> cb.invokestatic(CD_RT, "divf", MTD_I_II);
        default -> throw new IllegalStateException("Opcode " + op + " is not binary");
      }
      pushMat();
    }

    private boolean commutative(int op) {
      return switch (op) {
        case Op.ADD, Op.MULI, Op.MULU, Op.BAND, Op.BOR, Op.BXOR, Op.ADDF, Op.MULF -> true;
        default -> false;
      };
    }

    private int fold(int op, int l, int r) {
      return switch (op) {
        case Op.ADD -> l + r;
        case Op.SUB -> l - r;
        case Op.MULI, Op.MULU -> l * r;
        case Op.BAND -> l & r;
        case Op.BOR -> l | r;
        case Op.BXOR -> l ^ r;
        case Op.LSH -> l << r;
        case Op.RSHI -> l >> r;
        case Op.RSHU -> l >>> r;
        case Op.ADDF -> QvmJitRuntime.addf(l, r);
        case Op.SUBF -> QvmJitRuntime.subf(l, r);
        case Op.MULF -> QvmJitRuntime.mulf(l, r);
        case Op.DIVF -> QvmJitRuntime.divf(l, r);
        default -> throw new IllegalStateException("Opcode " + op + " is not binary");
      };
    }
  }
}
