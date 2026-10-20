package dev.bluevista.craftq3.engine.game;

import dev.bluevista.craftq3.engine.vm.Opcode;
import dev.bluevista.craftq3.engine.vm.QvmImage;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vm.Syscalls;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.QagameImport;
import dev.bluevista.craftq3.engine.vmabi.VmExports.Qagame;
import dev.bluevista.craftq3.engine.vmabi.VmImport;
import dev.bluevista.craftq3.engine.vmabi.layout.EntityStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.PlayerStateLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.SharedEntityLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.UserCmdLayout;
import dev.bluevista.craftq3.engine.vmabi.layout.VmStructs;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny game module written in Java for tests (no id code or data). Its QVM image only forwards
 * {@code vmMain} to a private trap that runs this class; the class then calls the server's real
 * import handlers with structures in guest memory, as a compiled qagame would.
 */
final class FakeGame implements GameProgram {
  static final int FORWARD = 4000;
  static final int ENTITIES = 0x10000, ENTITY_SIZE = 600, MAX_ENTITIES = 64;
  static final int CLIENTS = 0x20000, CLIENT_SIZE = 512;
  static final int SCRATCH = 0x30000, SCRATCH_SIZE = 0x8000;
  static final int MEMORY = 0x40000;
  static final float[] SPAWN = {-200, 0, 24};

  private static final SharedEntityLayout LAYOUT = SharedEntityLayout.Q3_132;

  final List<String> log = new ArrayList<>();
  int numEntities = 16;
  private Syscalls host;
  private QvmInstance vm;
  private int scratch;
  int locateCalls;

  @Override
  public String name() {
    return "fake-qagame";
  }

  @Override
  public AbiProfile profile() {
    return AbiProfile.Q3_132;
  }

  @Override
  public QvmInstance instantiate(Syscalls syscalls) {
    host = syscalls;
    vm =
        new QvmInstance(
            image(),
            (instance, number, args) ->
                number == FORWARD ? dispatch(args) : syscalls.call(instance, number, args),
            QvmGameProgram.LIMITS,
            dev.bluevista.craftq3.engine.common.time.Clock.system());
    return vm;
  }

  QvmMemory memory() {
    return vm.memory();
  }

  private static QvmImage image() {
    int frame = 8 + 4 * 9;
    List<int[]> code = new ArrayList<>();
    code.add(new int[] {Opcode.ENTER.ordinal(), frame});
    for (int i = 0; i < 9; i++) {
      code.add(new int[] {Opcode.LOCAL.ordinal(), frame + 8 + 4 * i});
      code.add(new int[] {Opcode.LOAD4.ordinal(), 0});
      code.add(new int[] {Opcode.ARG.ordinal(), 8 + 4 * i});
    }
    code.add(new int[] {Opcode.CONST.ordinal(), -1 - FORWARD});
    code.add(new int[] {Opcode.CALL.ordinal(), 0});
    code.add(new int[] {Opcode.LEAVE.ordinal(), frame});
    int length = 0;
    for (int[] op : code) length += 1 + Opcode.values()[op[0]].operandBytes();
    length = (length + 3) & ~3;
    int header = 32;
    ByteBuffer out = ByteBuffer.allocate(header + length + 4).order(ByteOrder.LITTLE_ENDIAN);
    out.putInt(QvmImage.MAGIC_V1).putInt(code.size()).putInt(header).putInt(length);
    out.putInt(header + length).putInt(4).putInt(0).putInt(MEMORY - 4);
    for (int[] op : code) {
      Opcode opcode = Opcode.values()[op[0]];
      out.put((byte) op[0]);
      if (opcode.operandBytes() == 4) out.putInt(op[1]);
      else if (opcode.operandBytes() == 1) out.put((byte) op[1]);
    }
    return QvmImage.parse("qagame", out.array(), 0x1000);
  }

  // ---- traps ------------------------------------------------------------------------------------

  int sys(VmImport entry, int... values) {
    int[] args = new int[Syscalls.MAX_ARGS];
    System.arraycopy(values, 0, args, 0, values.length);
    return host.call(vm, entry.number(), args);
  }

  int string(String text) {
    if (scratch + text.length() + 1 > SCRATCH_SIZE) scratch = 0;
    int address = SCRATCH + scratch;
    vm.memory().writeString(address, text, text.length() + 1);
    scratch += (text.length() + 4) & ~3;
    return address;
  }

  int vec(float... v) {
    int address = string("            ");
    VmStructs.writeVec3(vm.memory(), address, v);
    return address;
  }

  String read(int address) {
    return vm.memory().readString(address, 4096);
  }

  static int entity(int number) {
    return ENTITIES + number * ENTITY_SIZE;
  }

  static int client(int number) {
    return CLIENTS + number * CLIENT_SIZE;
  }

  String argv(int n) {
    int buffer = string(" ".repeat(255));
    sys(QagameImport.G_ARGV, n, buffer, 256);
    return read(buffer);
  }

  String cvar(String name) {
    int buffer = string(" ".repeat(255));
    sys(QagameImport.G_CVAR_VARIABLE_STRING_BUFFER, string(name), buffer, 256);
    return read(buffer);
  }

  /** Places entity {@code e} and links it. */
  void spawn(int e, float[] origin, float[] mins, float[] maxs, int contents, int svFlags) {
    QvmMemory m = vm.memory();
    int base = entity(e);
    m.writeInt(base + EntityStateLayout.NUMBER, e);
    VmStructs.writeVec3(m, base + EntityStateLayout.ORIGIN, origin);
    VmStructs.writeVec3(m, base + LAYOUT.currentOrigin(), origin);
    VmStructs.writeVec3(m, base + LAYOUT.mins(), mins);
    VmStructs.writeVec3(m, base + LAYOUT.maxs(), maxs);
    m.writeInt(base + LAYOUT.contents(), contents);
    m.writeInt(base + LAYOUT.svFlags(), svFlags);
    m.writeInt(base + LAYOUT.ownerNum(), 1023);
    sys(QagameImport.G_LINKENTITY, base);
  }

  void setSingleClient(int e, int value) {
    vm.memory().writeInt(entity(e) + LAYOUT.singleClient(), value);
  }

  void setOrigin2(int e, float[] origin2) {
    VmStructs.writeVec3(vm.memory(), entity(e) + EntityStateLayout.ORIGIN2, origin2);
  }

  void unlink(int e) {
    sys(QagameImport.G_UNLINKENTITY, entity(e));
  }

  float[] origin(int e) {
    float[] out = new float[3];
    VmStructs.readVec3(vm.memory(), entity(e) + LAYOUT.currentOrigin(), out);
    return out;
  }

  // ---- vmMain -----------------------------------------------------------------------------------

  private int dispatch(int[] a) {
    Qagame command = Qagame.values()[a[0]];
    switch (command) {
      case GAME_INIT -> {
        log.add("init " + a[1] + " restart=" + a[3]);
        scratch = 0;
        sys(QagameImport.G_CVAR_REGISTER, 0, string("fake_error"), string("0"), 0);
        locate();
        sys(QagameImport.G_SET_CONFIGSTRING, 20, string("fake-game"));
        int token = string(" ".repeat(1023));
        int tokens = 0;
        while (sys(QagameImport.G_GET_ENTITY_TOKEN, token, 1024) != 0) tokens++;
        log.add("tokens " + tokens);
        int door = entity(14);
        sys(QagameImport.G_SET_BRUSH_MODEL, door, string("*1"));
      }
      case GAME_SHUTDOWN -> log.add("shutdown restart=" + a[1]);
      case GAME_CLIENT_CONNECT -> {
        int buffer = string(" ".repeat(1023));
        sys(QagameImport.G_GET_USERINFO, a[1], buffer, 1024);
        String info = read(buffer);
        log.add("connect " + a[1] + " first=" + a[2] + " bot=" + a[3]);
        if (info.contains("\\deny\\")) return string("Denied by the test game");
        QvmMemory m = vm.memory();
        m.writeInt(client(a[1]) + PlayerStateLayout.CLIENT_NUM, a[1]);
        return 0;
      }
      case GAME_CLIENT_BEGIN -> {
        log.add("begin " + a[1]);
        QvmMemory m = vm.memory();
        VmStructs.writeVec3(m, client(a[1]) + PlayerStateLayout.ORIGIN, SPAWN);
        m.writeInt(client(a[1]) + PlayerStateLayout.VIEWHEIGHT, 26);
        spawn(a[1], SPAWN, new float[] {-15, -15, -24}, new float[] {15, 15, 32}, 0x2000000, 0);
      }
      case GAME_CLIENT_USERINFO_CHANGED -> log.add("userinfo " + a[1]);
      case GAME_CLIENT_DISCONNECT -> {
        log.add("disconnect " + a[1]);
        unlink(a[1]);
      }
      case GAME_CLIENT_COMMAND -> {
        String name = argv(0);
        log.add("command " + a[1] + " " + name);
        if (name.equals("echo"))
          sys(QagameImport.G_SEND_SERVER_COMMAND, a[1], string("print \"echo " + argv(1) + "\""));
      }
      case GAME_CLIENT_THINK -> {
        int cmd = string(" ".repeat(UserCmdLayout.BYTES));
        sys(QagameImport.G_GET_USERCMD, a[1], cmd, 0);
        var usercmd = UserCmdLayout.Q3_132.read(vm.memory(), cmd);
        QvmMemory m = vm.memory();
        m.writeInt(client(a[1]) + PlayerStateLayout.COMMAND_TIME, usercmd.serverTime());
        float[] origin = origin(a[1]);
        origin[0] += usercmd.forward();
        VmStructs.writeVec3(m, client(a[1]) + PlayerStateLayout.ORIGIN, origin);
        VmStructs.writeVec3(m, entity(a[1]) + EntityStateLayout.ORIGIN, origin);
        VmStructs.writeVec3(m, entity(a[1]) + LAYOUT.currentOrigin(), origin);
        sys(QagameImport.G_LINKENTITY, entity(a[1]));
      }
      case GAME_RUN_FRAME -> {
        log.add("frame " + a[1]);
        if (!cvar("fake_error").equals("0") && !cvar("fake_error").isEmpty())
          sys(QagameImport.G_ERROR, string("fake failure at " + a[1]));
      }
      case GAME_CONSOLE_COMMAND -> {
        String name = argv(0);
        if (name.equals("fakecmd")) {
          log.add("console fakecmd " + argv(1));
          return 1;
        }
        if (name.equals("fakebot")) {
          int slot = sys(QagameImport.G_BOT_ALLOCATE_CLIENT);
          log.add("bot " + slot);
          if (slot < 0) return 1;
          sys(QagameImport.G_SET_USERINFO, slot, string("\\name\\" + argv(1)));
          dispatch(new int[] {Qagame.GAME_CLIENT_CONNECT.command(), slot, 1, 1, 0, 0, 0, 0, 0});
          dispatch(new int[] {Qagame.GAME_CLIENT_BEGIN.command(), slot, 0, 0, 0, 0, 0, 0, 0});
          return 1;
        }
        return 0;
      }
      case BOTAI_START_FRAME -> log.add("botframe " + a[1]);
    }
    return 0;
  }

  private void locate() {
    locateCalls++;
    sys(QagameImport.G_LOCATE_GAME_DATA, ENTITIES, numEntities, ENTITY_SIZE, CLIENTS, CLIENT_SIZE);
  }

  /** Grows the entity count and re-announces it, as {@code G_Spawn} does. */
  void entities(int count) {
    numEntities = count;
    locate();
  }
}
