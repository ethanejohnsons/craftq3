import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.*;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.core.command.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.platform.net.UdpTransport;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.UserCommand;
import java.io.IOException;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Private UDP to unchanged native qagame, with its matching original cgame in the Java VM. */
public class AuditRemoteApplication {
  private static final class Audio implements AudioBackend {
    int sounds; long voices;
    public int register(String name, PcmSound sound) { return ++sounds; }
    public long play(Playback playback) { return ++voices; }
    public void updateEntity(int entity, Vec3 origin) {}
    public void beginFrame() {}
    public void submitLoop(Loop loop) {}
    public void endFrame(Listener listener) {}
    public void clearLoops() {}
    public void stop(long voice) {}
    public void stopAll() {}
    public void volume(float gain) {}
    public Diagnostics diagnostics() { return new Diagnostics(true, sounds, 0, 0, 0, voices, 0, "CPU audit"); }
    public void close() {}
  }

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Private audit server port required");
    Path games = Path.of(args[1]), out = Path.of(args[2]);
    boolean pure = args.length > 3 && Boolean.parseBoolean(args[3]);
    var audio = new Audio();
    try (var fs = Pk3FileSystem.mount(games, "baseq3");
         var saved = new GameFileStore(out.resolve("saved"));
         var session = new QuakeSession(fs, saved, audio, null, null, "Application QA", 1280, 720)) {
      Q3Ui initialUi = session.ui();
      String initialUiPack = fs.which(new VirtualPath("vm/ui.qvm")).orElseThrow().container();
      if (pure && !initialUiPack.endsWith("zz_client_override.pk3"))
        throw new AssertionError("Pure audit did not begin with the disallowed local override");
      if (session.advance(16,1280,720).commands().isEmpty()) throw new AssertionError("No original main menu");
      session.command("connect invalid/host");
      session.advance(16,1280,720);
      if (session.networked() || session.playing()) throw new AssertionError("Invalid address changed game");
      session.command("connect 127.0.0.1:"+port);
      int firstFrames=0, secondFrames=0, views=0, quads=0;
      int initialGeneration=session.generation();
      boolean held=false, restarted=false, changed=false, menuTest=false, backgroundTest=false;
      long deadline=System.nanoTime()+35_000_000_000L;
      while(System.nanoTime()<deadline && secondFrames<90) {
        // Exercise the actual screen tick's keepalive path independently of presentation.
        session.networkTick(1280,720);
        var frame=session.advance(16,1280,720);
        int frameViews=0;
        for(var command:frame.commands()) {
          if(command instanceof CgameFrame.View) {views++;frameViews++;}
          else if(command instanceof CgameFrame.Quad) quads++;
        }
        if(session.playing() && session.networked() && frameViews>0) {
          if(session.server()!=null || session.cvars().integer("sv_running")!=0)
            throw new AssertionError("Remote application created a local game server");
          if (pure && (session.cvars().integer("sv_pure") != 1 || session.ui() == initialUi
              || !fs.which(new VirtualPath("vm/ui.qvm")).orElseThrow().container().endsWith("y_qa_ui.pk3")
              || !fs.which(new VirtualPath("vm/cgame.qvm")).orElseThrow().container().endsWith("z_qa_cgame.pk3")))
            throw new AssertionError("Pure view retained client-only modules or the old UI");
          if(!held) {
            session.input().key('w',true,session.inputTime());
            session.command("+attack"); held=true;
          }
          if(session.world().mapName().contains("q3dm17")) secondFrames++;
          else firstFrames++;
          if(firstFrames==60 && !menuTest) {
            int before=session.time();
            session.openMenu();
            if(!session.menuVisible()) throw new AssertionError("No original remote in-game menu");
            session.cvars().set("cl_paused","1",CvarSystem.Source.ENGINE);
            for(int i=0;i<10;i++) {session.advance(16,1280,720);Thread.sleep(16);}
            if(session.time()<=before) throw new AssertionError("Remote game paused in menu");
            session.ui().setMenu(Q3Ui.Menu.NONE);
            session.input().key('w',true,session.inputTime());
            session.command("+attack"); menuTest=true;
          }
          if(firstFrames>=130 && !restarted) {restarted=true;System.out.println("RESTART_LEVEL");}
          if(firstFrames>=100 && !backgroundTest) {
            for(int i=0;i<80;i++) {
              session.command("score");
              session.backgroundFrame(1280,720);
              Thread.sleep(50);
            }
            if(!session.networked() || !session.playing()) throw new AssertionError("Background cgame stopped");
            session.input().key('w',true,session.inputTime());session.command("+attack");
            backgroundTest=true;
          }
          if(firstFrames>=230 && !changed) {changed=true;System.out.println("CHANGE_LEVEL q3dm17");}
        }
        if(!session.networked() && !session.playing())
          throw new AssertionError("Application disconnected: "+session.consoleLines());
        Thread.sleep(16);
      }
      if(firstFrames<230 || secondFrames<90 || views<500 || quads<1000 || !menuTest
          || session.generation()<initialGeneration+3)
        throw new AssertionError("Incomplete remote application: "+firstFrames+" / "+secondFrames);
      session.command("disconnect");
      var main=session.advance(16,1280,720);
      if(session.networked() || session.client()!=null || session.server()!=null || !session.menuVisible()
          || main.commands().isEmpty()) throw new AssertionError("Disconnect did not restore original main menu");
      if (pure && !fs.which(new VirtualPath("vm/ui.qvm")).orElseThrow().container().equals(initialUiPack))
        throw new AssertionError("Disconnect did not restore the local pack view");
      // Deterministically place a physical release between DNS completion and failed socket open.
      // These are authored host failures; the original game VM and its gameplay state are untouched.
      var begin=QuakeSession.class.getDeclaredMethod("beginConnection",String.class);
      begin.setAccessible(true);begin.invoke(session,"0.0.0.0");
      Thread.sleep(25);
      int releasedAt=session.inputTime();
      session.input().releaseAll(releasedAt);
      session.networkTick(1280,720);
      if(session.networked() || session.inputTime()<releasedAt)
        throw new AssertionError("Failed socket open lost the physical input clock");
      var fail=QuakeSession.class.getDeclaredMethod("remoteFailure",Exception.class);
      fail.setAccessible(true);
      fail.invoke(session,new IllegalStateException("Server disconnected - first\nsecond\0\u2603"));
      String display=session.cvars().string("com_errorMessage");
      if(display.indexOf('\n')>=0 || display.indexOf('\0')>=0 || display.chars().anyMatch(c->c>255)
          || session.advance(16,1280,720).commands().isEmpty())
        throw new AssertionError("Error text could not return safely to the original menu");
      session.cvars().set("bot_enable","0",CvarSystem.Source.ENGINE);
      session.command("map q3dm1");
      session.advance(16,1280,720);
      if(!session.playing() || session.networked() || session.server()==null)
        throw new AssertionError("Could not start local game after remote disconnect");
      session.input().key('w',true,session.inputTime());
      for(int i=0;i<60;i++) if(session.advance(16,1280,720).commands().isEmpty())
        throw new AssertionError("Local game omitted presentation");
      session.command("disconnect");session.advance(16,1280,720);
      String result="{\"firstMapFrames\":"+firstFrames+",\"secondMapFrames\":"+secondFrames
          +",\"views\":"+views+",\"quads\":"+quads+",\"voices\":"+audio.voices
          +",\"pureServer\":"+pure+",\"localFramesAfterDisconnect\":60,\"remoteMenuKeptPlaying\":true,\"failureRecovery\":true,\"backgroundFrames\":80}";
      Files.writeString(out.resolve("application-result.json"),result+"\n");
      System.out.println("PASS "+result);
    }
  }
}
