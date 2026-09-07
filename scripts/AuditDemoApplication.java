import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Ui;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.demo.*;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.core.net.ServerMessageCodec;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import java.nio.file.*;
import java.util.*;

/** Original cgame/UI demo record and playback through the actual Fabric CPU application. */
public class AuditDemoApplication {
  private static final Set<String> playedMaps = new HashSet<>();
  private static final class Audio implements AudioBackend {
    int sounds;long voices;
    public int register(String name,PcmSound sound){return ++sounds;}
    public long play(Playback playback){return ++voices;}
    public void updateEntity(int entity,Vec3 origin){}
    public void beginFrame(){}
    public void submitLoop(Loop loop){}
    public void endFrame(Listener listener){}
    public void clearLoops(){}
    public void stop(long voice){}
    public void stopAll(){}
    public void volume(float value){}
    public Diagnostics diagnostics(){return new Diagnostics(true,sounds,0,0,0,voices,0,"CPU demo audit");}
    public void close(){}
  }
  public static void main(String[] args)throws Exception {
    int port=Integer.parseInt(args[0]);Path games=Path.of(args[1]),out=Path.of(args[2]);
    Files.createDirectories(out);
    try(var fs=Pk3FileSystem.mount(games,"baseq3");var saved=new GameFileStore(out.resolve("saved"));
        var demos=new DemoFileStore(out.resolve("demos"));
        var session=new QuakeSession(fs,saved,new Audio(),null,null,"Demo QA",640,480,demos)) {
      for(int i=1;i<=5;i++)session.cvars().set("sv_master"+i,"",CvarSystem.Source.ENGINE);
      int api=session.ui().apiVersion();
      session.cvars().set("bot_enable","0",CvarSystem.Source.ENGINE);
      session.command("map q3dm1");frame(session);
      session.command("record localclip");frame(session);
      check(session.cvars().integer("cl_demoRecording")==1,"Local recording did not start");
      session.input().key('w',true,session.inputTime());session.command("+attack");
      for(int i=0;i<180;i++)frame(session);
      session.command("stoprecord");frame(session);
      check(session.cvars().integer("cl_demoRecording")==0,"Recording did not stop");
      int localSnapshots=validate(demos,"localclip.dm_68");
      session.command("disconnect");frame(session);
      session.command("demo localclip");frame(session);
      int localViews=play(session,true);
      check(localViews>100,"Too few local playback views");
      // The original Demos menu discovers the isolated store, and Play supplies the command.
      key(session,27);session.ui().setMenu(Q3Ui.Menu.MAIN);frame(session);
      key(session,133);key(session,133);key(session,133);key(session,13);frame(session);
      session.menuMouse(-10000,-10000);session.menuMouse(580,445);frame(session);key(session,178);
      check(session.cvars().integer("cl_demoPlaying")==1,"Original Demos menu did not start playback: "+session.consoleLines());
      int menuViews=play(session,false);
      int remoteSnapshots=0,remoteViews=0;
      if(api>=4) {
        session.command("connect 127.0.0.1:"+port);
        long deadline=System.nanoTime()+15_000_000_000L;
        while(System.nanoTime()<deadline&&(!session.networked()||!session.playing())){frame(session);Thread.sleep(16);}
        check(session.networked()&&session.playing(),"No private remote connection");
        for(int i=0;i<20;i++){frame(session);Thread.sleep(16);}
        session.command("record remoteclip");frame(session);
        session.input().key('w',true,session.inputTime());session.command("+attack");
        for(int i=0;i<360;i++){
          if(i==90)System.out.println("RESTART_LEVEL");
          if(i==180)System.out.println("CHANGE_LEVEL q3dm17");
          frame(session);Thread.sleep(16);
        }
        check(session.world().mapName().contains("q3dm17"),"Native map change missing");
        session.command("stoprecord");frame(session);
        remoteSnapshots=validate(demos,"remoteclip.dm_68");
        session.command("disconnect");frame(session);
        session.command("demo remoteclip");frame(session);
        remoteViews=play(session,false);check(remoteViews>100,"Too few remote playback views");
        check(playedMaps.stream().anyMatch(map->map.contains("q3dm1.bsp"))&&playedMaps.stream().anyMatch(map->map.contains("q3dm17.bsp")),"Demo omitted a recorded level: "+playedMaps);
      }
      session.cvars().set("timedemo","1",CvarSystem.Source.ENGINE);
      session.cvars().set("nextdemo","set craftq3_demo_chain passed",CvarSystem.Source.ENGINE);
      session.command("demo localclip");frame(session);
      check(play(session,false)>100,"Timedemo did not render");
      frame(session);check(session.cvars().string("craftq3_demo_chain").equals("passed"),"Demo chain command was lost");
      session.cvars().set("timedemo","0",CvarSystem.Source.ENGINE);
      session.command("demo localclip");frame(session);
      check(!session.demoKey(133,true)&&!session.demoKey(97,false),"Non-exit key stopped demo");
      check(session.demoKey(97,true),"Letter did not request demo exit");frame(session);
      check(session.menuVisible()&&!session.playing(),"Demo key did not restore menu");
      missingMap(demos);
      session.command("demo missing-map");frame(session);
      check(session.cvars().integer("cl_demoPlaying")==0&&session.menuVisible(),"Missing demo map did not recover menu");
      session.command("demo missing-demo");frame(session);
      check(session.cvars().integer("cl_demoPlaying")==0&&session.menuVisible(),"Failed demo did not recover menu");
      failedReplacement(games,out);
      String result="{\"uiApi\":"+api+",\"localSnapshots\":"+localSnapshots+",\"localViews\":"+localViews+",\"menuViews\":"+menuViews+",\"remoteSnapshots\":"+remoteSnapshots+",\"remoteViews\":"+remoteViews+",\"recoveredMenu\":true}";
      Files.writeString(out.resolve("demo-application-result.json"),result+"\n");System.out.println("PASS "+result);
    }
  }
  private static int validate(DemoFileStore store,String name)throws Exception {
    int snapshots=0;boolean game=false;
    try(var reader=new Protocol68DemoReader(store.openRead(new VirtualPath(name)).orElseThrow())){
      for(var record=reader.next();record.isPresent();record=reader.next())
        for(var operation:record.orElseThrow().message().operations()) {
          if(operation instanceof ServerMessageCodec.GameState)game=true;
          if(operation instanceof ServerMessageCodec.Frame){check(game,"Snapshot before game");snapshots++;}
        }
      check(reader.end()==DemoReader.End.MARKER,"Missing clean record trailer");
    }
    check(snapshots>=30,"Too few recorded snapshots: "+snapshots);return snapshots;
  }
  private static void failedReplacement(Path games,Path out)throws Exception {
    Path root=out.resolve("limited-demos");Files.createDirectories(root);
    byte[] original={1,2,3,4,5,6,7,8};
    Files.write(root.resolve("keep.dm_68"),original);
    try(var fs=Pk3FileSystem.mount(games,"baseq3");
        var demos=new DemoFileStore(root,new DemoFileStore.Limits(8,8,1));
        var session=new QuakeSession(fs,null,new Audio(),null,null,"Quota QA",640,480,demos)) {
      session.cvars().set("bot_enable","0",CvarSystem.Source.ENGINE);
      session.command("map q3dm1");frame(session);
      session.command("record keep");frame(session);
      check(session.cvars().integer("cl_demoRecording")==0,"Failed start remained recording");
      check(Arrays.equals(original,Files.readAllBytes(root.resolve("keep.dm_68"))),"Failed start destroyed existing demo");
      try(var entries=Files.list(root)){check(entries.count()==1,"Failed start leaked temporary");}
    }
  }
  private static void missingMap(DemoFileStore store)throws Exception {
    var game=new ServerMessageCodec.GameState(0,Map.of(0,"\\mapname\\absent-craftq3-demo"),
        dev.bluevista.craftq3.core.net.SnapshotDeltaCodec.Baselines.EMPTY,0,12345);
    var message=new dev.bluevista.craftq3.core.net.MessageWriter();
    ServerMessageCodec.write(message,new ServerMessageCodec.Message(0,List.of(game)),game.baselines());
    try(var output=store.openAtomicWrite(new VirtualPath("missing-map.dm_68"));var writer=new DemoWriter(output)) {
      writer.append(new DemoRecord(0,message.bytes()));writer.finish();output.commit();
    }
  }
  private static int play(QuakeSession s,boolean freeze)throws Exception {
    playedMaps.clear();
    int views=0,frames=0;boolean frozen=false;
    while(s.cvars().integer("cl_demoPlaying")!=0&&frames++<1500){
      var frame=frame(s);
      if(s.cvars().integer("cl_demoPlaying")==0)break;
      check(s.server()==null&&!s.networked(),"Demo created a game server or network connection");
      views+=(int)frame.commands().stream().filter(CgameFrame.View.class::isInstance).count();
      playedMaps.add(s.world().mapName());
      if(freeze&&!frozen&&views>30){
        s.cvars().set("cl_freezeDemo","1",CvarSystem.Source.ENGINE);int time=s.time();
        for(int i=0;i<10;i++)frame(s);
        check(s.time()==time,"Demo freeze advanced render time");
        s.cvars().set("cl_freezeDemo","0",CvarSystem.Source.ENGINE);frozen=true;
      }
    }
    check(frames<1500,"Demo did not end");
    check(s.menuVisible()&&!s.playing()&&!s.networked(),"Demo EOF did not restore menu");return views;
  }
  private static CgameFrame frame(QuakeSession s){return s.advance(16,640,480);}
  private static void key(QuakeSession s,int key){s.menuKey(key,true);s.menuKey(key,false);frame(s);}
  private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
