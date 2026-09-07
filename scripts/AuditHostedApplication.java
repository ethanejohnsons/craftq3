import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.GameFileStore;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import java.nio.file.*;
import java.util.*;

/** Actual host and remote Fabric CPU sessions; original QVMs and private UDP only. */
class AuditHostedApplication {
  static class Audio implements AudioBackend {
    int sounds;long voices;
    public int register(String name,PcmSound sound){return ++sounds;}
    public long play(Playback playback){return ++voices;}
    public void updateEntity(int entity,Vec3 origin){}public void beginFrame(){}public void submitLoop(Loop loop){}
    public void endFrame(Listener listener){}public void clearLoops(){}public void stop(long voice){}public void stopAll(){}public void volume(float value){}
    public Diagnostics diagnostics(){return new Diagnostics(true,sounds,0,0,0,voices,0,"Hosted CPU audit");}public void close(){}
  }
  static int views,firstMapFrames,secondMapFrames;
  public static void main(String[] args)throws Exception {
    Path games=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
    try(var hostFs=Pk3FileSystem.mount(games,"baseq3");var remoteFs=Pk3FileSystem.mount(games,"baseq3");
        var hostSaved=new GameFileStore(out.resolve("host-saved"));var remoteSaved=new GameFileStore(out.resolve("remote-saved"));
        var host=new QuakeSession(hostFs,hostSaved,new Audio(),null,null,"Host QA",640,480);
        var remote=new QuakeSession(remoteFs,remoteSaved,new Audio(),null,null,"Remote QA",640,480)) {
      for(var s:List.of(host,remote))for(int i=1;i<=5;i++)s.cvars().set("sv_master"+i,"",CvarSystem.Source.ENGINE);
      host.cvars().set("bot_enable","0",CvarSystem.Source.ENGINE);
      // Disable unrelated discovery requests while exercising original server creation.
      host.commands().unregister("localservers");
      host.commands().unregister("globalservers");
      host.commands().register("localservers",command->{});
      host.commands().register("globalservers",command->{});
      key(host,27);host.ui().setMenu(dev.bluevista.craftq3.client.Q3Ui.Menu.MAIN);host.advance(16,640,480);
      key(host,133);key(host,13);
      click(host,450,440);click(host,580,440);click(host,580,440);
      for(int i=0;i<4;i++)host.advance(16,640,480);
      check(host.hosting(),"No hosted listener: "+host.consoleLines());
      var address=host.hostedAddress().orElseThrow();check(address.getAddress().isLoopbackAddress(),"Audit listener is not private");
      remote.command("connect 127.0.0.1:"+address.getPort());
      join(host,remote,"q3dm1");
      byte[] module=hostFs.read(new dev.bluevista.craftq3.core.fs.VirtualPath("vm/qagame.qvm"));
      for(var source:List.of(host,remote)) {
        try {source.bridgeLoadout(module);throw new AssertionError("Network match exported a bridge loadout");}
        catch(IllegalStateException expected){}
      }
      for(int i=0;i<90;i++)step(host,remote);
      int flags=host.server().snapshotFlags();host.command("map_restart 0");step(host,remote);
      check(flags!=host.server().snapshotFlags(),"Host did not restart");
      for(int i=0;i<60;i++)step(host,remote);
      check(remote.playing()&&remote.networked(),"Restart lost remote session: "+remote.consoleLines());
      host.openMenu();int before=host.server().frameNumber();for(int i=0;i<20;i++)step(host,remote);
      check(host.server().frameNumber()>before,"Host menu paused the network match");
      for(int i=0;i<20;i++){host.backgroundFrame(640,480);remote.advance(16,640,480);Thread.sleep(16);}
      host.command("map q3dm17");step(host,remote);join(host,remote,"q3dm17");
      check(host.hostedAddress().orElseThrow().getPort()==address.getPort(),"Map change replaced listener port");
      for(int i=0;i<90;i++)step(host,remote);
      check(host.hostedClients().stream().filter(c->c.active()).count()==1,"Remote peer was lost");
      remote.command("disconnect");for(int i=0;i<20;i++)step(host,remote);
      check(host.hostedClients().stream().noneMatch(c->c.active()),"Remote disconnect left player active");
      host.command("set dedicated 1; map q3dm1");step(host,remote);
      check(host.hosting()&&host.server()!=null&&!host.playing(),"Dedicated host started local cgame");
      remote.command("connect 127.0.0.1:"+address.getPort());join(host,remote,"q3dm1");
      for(int i=0;i<45;i++)step(host,remote);
      host.command("disconnect");
      for(int i=0;i<60&&remote.networked();i++)step(host,remote);
      check(!host.hosting()&&!remote.networked()&&remote.menuVisible(),"Server shutdown did not return remote menu: "+remote.consoleLines());
      String result="{\"uiApi\":"+host.ui().apiVersion()+",\"firstMapFrames\":"+firstMapFrames+",\"secondMapFrames\":"+secondMapFrames+",\"views\":"+views+",\"dedicated\":true,\"restart\":true,\"shutdownMenu\":true}";
      Files.writeString(out.resolve("hosted-application-result.json"),result+"\n");System.out.println("PASS "+result);
    }
  }
  static void join(QuakeSession host,QuakeSession remote,String map)throws Exception {
    long deadline=System.nanoTime()+20_000_000_000L;
    while(System.nanoTime()<deadline) {
      step(host,remote);
      if(remote.playing()&&remote.networked()&&remote.world().mapName().contains(map)
          &&host.hostedClients().stream().anyMatch(c->c.active()))return;
    }
    throw new AssertionError("Remote join failed: "+remote.consoleLines()+" HOST "+host.consoleLines());
  }
  static void step(QuakeSession host,QuakeSession remote)throws Exception {
    host.advance(16,640,480);var frame=remote.advance(16,640,480);
    if(remote.playing()){
      views+=(int)frame.commands().stream().filter(CgameFrame.View.class::isInstance).count();
      if(remote.world().mapName().contains("q3dm17"))secondMapFrames++;else firstMapFrames++;
    }
    Thread.sleep(16);
  }
  static void key(QuakeSession s,int key){s.menuKey(key,true);s.menuKey(key,false);s.advance(16,640,480);}
  static void click(QuakeSession s,int x,int y){s.menuMouse(-10000,-10000);s.menuMouse(x,y);s.advance(16,640,480);key(s,178);}
  static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
}
