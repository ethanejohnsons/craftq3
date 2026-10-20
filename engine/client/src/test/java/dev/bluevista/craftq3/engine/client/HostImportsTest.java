package dev.bluevista.craftq3.engine.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.bluevista.craftq3.engine.client.host.CgameHost;
import dev.bluevista.craftq3.engine.client.host.UiHost;
import dev.bluevista.craftq3.engine.client.pipeline.SnapshotPipeline;
import dev.bluevista.craftq3.engine.client.pipeline.UserCmdHistory;
import dev.bluevista.craftq3.engine.fs.SearchPath;
import dev.bluevista.craftq3.engine.net.delta.SnapshotDeltaCodec;
import dev.bluevista.craftq3.engine.net.msg.ServerMessageCodec;
import dev.bluevista.craftq3.engine.vm.QvmInstance;
import dev.bluevista.craftq3.engine.vm.QvmMemory;
import dev.bluevista.craftq3.engine.vmabi.AbiProfile;
import dev.bluevista.craftq3.engine.vmabi.CgameImport;
import dev.bluevista.craftq3.engine.vmabi.ImportTable;
import dev.bluevista.craftq3.engine.vmabi.Policy;
import dev.bluevista.craftq3.engine.vmabi.UiImport;
import dev.bluevista.craftq3.engine.vmabi.VmImport;
import dev.bluevista.craftq3.engine.vmabi.VmModule;
import dev.bluevista.craftq3.engine.vmabi.layout.UiClientStateLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Both hosts bind every import their module's table maps in both ABI profiles (the dispatcher
 * refuses to start otherwise), and the ui host's client-state, configstring, key and CD-key imports
 * behave as the original's.
 */
final class HostImportsTest {
  private static final String KEY = "abcdefgh12345678";

  @TempDir Path install;

  private SearchPath mount() throws Exception {
    Files.createDirectories(install.resolve("baseq3/vm"));
    Files.write(install.resolve("baseq3/vm/ui.qvm"), TestQvm.returning(6));
    Files.write(install.resolve("baseq3/vm/cgame.qvm"), TestQvm.snapshotCgame());
    Files.writeString(install.resolve("baseq3/q3key"), KEY + "\n", StandardCharsets.ISO_8859_1);
    return SearchPath.mount(install, "baseq3");
  }

  @ParameterizedTest
  @EnumSource(AbiProfile.class)
  void everyHostImportIsBound(AbiProfile profile) throws Exception {
    try (SearchPath path = mount();
        ClientRig rig = new ClientRig(path.view(), install, true)) {
      rig.console.cvars().set("vm_abi", profile.key());
      UiHost ui = new UiHost(rig.services, UiHost.Link.DISCONNECTED);
      ui.start();
      assertEquals(profile, ui.profile());
      assertEquals(UiHost.State.RUNNING, ui.state());
      SnapshotPipeline pipeline = new SnapshotPipeline();
      pipeline.accept(
          1,
          new ServerMessageCodec.Message(
              0,
              List.of(
                  new ServerMessageCodec.GameState(
                      0, Map.of(1, "\\sv_serverid\\3"), SnapshotDeltaCodec.Baselines.EMPTY, 0, 0))),
          null,
          n -> 0);
      CgameHost cgame = new CgameHost(rig.services, pipeline, new UserCmdHistory(), text -> {});
      cgame.start();
      assertEquals(profile, cgame.profile());
      for (VmModule module : List.of(VmModule.UI, VmModule.CGAME)) {
        ImportTable table = ImportTable.of(module, profile);
        for (VmImport entry : table.entries())
          if (entry.policy().kind() == Policy.Kind.IMPLEMENTED_BY_HOST)
            assertTrue(
                (module == VmModule.UI ? ui : cgame).dispatcher().table().contains(entry),
                entry.name());
      }
      ui.shutdown();
      cgame.shutdown();
    }
  }

  private static int call(UiHost ui, UiImport entry, int... args) {
    QvmInstance vm = ui.instance();
    int[] words = new int[16];
    System.arraycopy(args, 0, words, 0, args.length);
    return ui.dispatcher().call(vm, ui.dispatcher().table().numberOf(entry), words);
  }

  @Test
  void uiImportsReportStateConfigstringsKeysAndTheCdKey() throws Exception {
    try (SearchPath path = mount();
        ClientRig rig = new ClientRig(path.view(), install, true)) {
      UiHost ui =
          new UiHost(
              rig.services,
              new UiHost.Link() {
                @Override
                public UiClientStateLayout.UiClientState clientState() {
                  return new UiClientStateLayout.UiClientState(
                      UiClientStateLayout.CA_CONNECTING, 2, -1, "server:27960", "", "waiting");
                }

                @Override
                public String configString(int index) {
                  return index == 0 ? "\\mapname\\q3dm1" : "";
                }
              });
      ui.start();
      QvmMemory memory = ui.instance().memory();
      int buffer = 2048;
      call(ui, UiImport.UI_GETCLIENTSTATE, buffer);
      var state = UiClientStateLayout.read(memory, buffer);
      assertEquals(UiClientStateLayout.CA_CONNECTING, state.connState());
      assertEquals("server:27960", state.serverName());
      assertEquals(1, call(ui, UiImport.UI_GETCONFIGSTRING, 0, buffer, 64));
      assertEquals("\\mapname\\q3dm1", memory.readString(buffer, 64));
      assertEquals(0, call(ui, UiImport.UI_GETCONFIGSTRING, 5, buffer, 64));
      assertEquals("", memory.readString(buffer, 64));

      memory.writeString(buffer, "+attack", 64);
      call(ui, UiImport.UI_KEY_SETBINDING, 'k', buffer);
      call(ui, UiImport.UI_KEY_GETBINDINGBUF, 'k', buffer + 100, 64);
      assertEquals("+attack", memory.readString(buffer + 100, 64));
      call(ui, UiImport.UI_KEY_KEYNUMTOSTRINGBUF, 178, buffer, 64);
      assertEquals("MOUSE1", memory.readString(buffer, 64));
      call(ui, UiImport.UI_KEY_SETCATCHER, 2);
      assertEquals(2, call(ui, UiImport.UI_KEY_GETCATCHER));

      call(ui, UiImport.UI_GET_CDKEY, buffer, 17);
      assertEquals(KEY, memory.readString(buffer, 17));
      assertEquals(1, call(ui, UiImport.UI_VERIFY_CDKEY, buffer, 0));
      memory.writeString(buffer, "zzzzzzzzzzzzzzzz", 17);
      call(ui, UiImport.UI_SET_CDKEY, buffer);
      call(ui, UiImport.UI_GET_CDKEY, buffer + 100, 17);
      assertEquals("zzzzzzzzzzzzzzzz", memory.readString(buffer + 100, 17));
      assertFalse(rig.printed.stream().anyMatch(line -> line.contains(KEY)), "key logged");

      memory.writeString(buffer, "gfx/missing/shader", 64);
      assertEquals(0, call(ui, UiImport.UI_R_REGISTERSHADERNOMIP, buffer), "default shader");
      assertEquals(0, ui.degradationTotal());
      ui.shutdown();
    }
  }

  @Test
  void cgameImportsReadTheGamestateAndUserCommands() throws Exception {
    try (SearchPath path = mount();
        ClientRig rig = new ClientRig(path.view(), install, true)) {
      SnapshotPipeline pipeline = new SnapshotPipeline();
      pipeline.accept(
          4,
          new ServerMessageCodec.Message(
              0,
              List.of(
                  new ServerMessageCodec.GameState(
                      7,
                      Map.of(1, "\\sv_serverid\\3", 20, "first"),
                      SnapshotDeltaCodec.Baselines.EMPTY,
                      2,
                      0),
                  new ServerMessageCodec.Command(8, "cs 20 \"second\""))),
          null,
          n -> 0);
      UserCmdHistory commands = new UserCmdHistory();
      CgameHost cgame = new CgameHost(rig.services, pipeline, commands, text -> {});
      cgame.start();
      assertEquals("first", cgame.configStrings().get(20), "cs waits until executed");
      int number = cgame.dispatcher().table().numberOf(CgameImport.CG_GETSERVERCOMMAND);
      assertEquals(1, cgame.dispatcher().call(cgame.instance(), number, new int[] {8, 0, 0}));
      assertEquals("second", cgame.configStrings().get(20));
      assertThrows(
          RuntimeException.class,
          () -> cgame.dispatcher().call(cgame.instance(), number, new int[] {9, 0, 0}),
          "a command not received drops the cgame");
      cgame.shutdown();
    }
  }
}
