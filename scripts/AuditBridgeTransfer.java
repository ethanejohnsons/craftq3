import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.render.BspSceneBuilder;
import dev.bluevista.craftq3.render.MaterialLibrary;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.collision.BoxTraceWorld;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.GameFileStore;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.server.*;
import java.nio.file.*;
import java.util.*;
import java.time.Clock;

/** Original local match exports and destination QVM gameplay; no game data is changed. */
class AuditBridgeTransfer {
  static class Audio implements AudioBackend {
    int sounds;long voices;
    public int register(String name,PcmSound sound){return ++sounds;}
    public long play(Playback playback){return ++voices;}
    public void updateEntity(int entity,Vec3 origin){}public void beginFrame(){}public void submitLoop(Loop loop){}
    public void endFrame(Listener listener){}public void clearLoops(){}public void stop(long voice){}public void stopAll(){}public void volume(float value){}
    public Diagnostics diagnostics(){return new Diagnostics(true,sounds,0,0,0,voices,0,"Hosted CPU audit");}public void close(){}
  }

  static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  static void reject(QuakeSession source,byte[] module) throws Exception {
    byte[] before=source.server()==null?null:source.server().playerState(0);
    try {source.bridgeLoadout(module);throw new AssertionError("Unsupported source accepted");}
    catch(IllegalArgumentException|IllegalStateException expected){}
    if(before!=null)check(Arrays.equals(before,source.server().playerState(0)),"Rejected export changed source");
  }
  public static void main(String[] args)throws Exception {
    Path games=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
    try(var fs=Pk3FileSystem.mount(games,"baseq3");var saved=new GameFileStore(out.resolve("saved"));
        var source=new QuakeSession(fs,saved,new Audio(),null,null,"Transfer QA",640,480)) {
      byte[] module=fs.read(new VirtualPath("vm/qagame.qvm"));
      reject(source,module);
      source.command("set net_enabled 0; set bot_enable 0; devmap q3dm17");source.advance(16,640,480);
      check(source.playing()&&!source.hosting(),"Local source did not start");
      source.command("give all; give Mega Health; give Medkit");
      for(int i=0;i<6;i++)source.advance(16,640,480);
      source.command("weapon 5");for(int i=0;i<30;i++)source.advance(16,640,480);
      source.command("give Quad Damage");source.advance(16,640,480);
      reject(source,new byte[]{1,2,3});
      byte[] before=source.server().playerState(0);
      PlayerLoadout loadout=source.bridgeLoadout(module);
      check(Arrays.equals(before,source.server().playerState(0)),"Export mutated live match");
      check(loadout.weapon()==5&&loadout.armor()>0&&loadout.holdable()>0&&loadout.powerupMillis().getFirst()>0,"Incomplete source loadout: "+loadout);
      source.command("minecraft");source.advance(0,640,480);
      check(source.takeBridgeRequest()&&!source.takeBridgeRequest(),"Command not delivered exactly once");
      check(source.playing(),"Command closed source before host admission");
      var vars=new CvarSystem();vars.register("sv_cheats","1",CvarSystem.ROM|CvarSystem.SYSTEMINFO);vars.register("bot_enable","0",0);vars.register("sv_maxclients","2",0);
      var ground=new BoxTraceWorld(new Vec3(-10000,-10000,-32),new Vec3(10000,10000,0),1,0,1022);
      PlayerLoadout returning;
      try(var targetFs=Pk3FileSystem.mount(games,"baseq3");var target=new Q3Server(targetFs,"craftq3_bridge",ExternalWorld.combat(ground,new Vec3(0,0,25),0),null,x->{},Clock.systemUTC(),null,vars)) {
        target.initialize(1000,42);target.connect(0,Map.of("name","Transfer QA","ip","localhost"));target.restoreLoadout(loadout);
        check(loadout.equals(PlayerLoadout.capture(target.playerState(0),target.time())),"Destination changed inventory");
        int now=target.time()+8;target.userCommand(0,new UserCommand(now,0,0,0,1,5,0,0,0));target.runFrame(now);
        var after=PlayerLoadout.capture(target.playerState(0),target.time());
        check(after.ammo().get(5)==loadout.ammo().get(5)-1,"Original rocket not fired after transfer");
        check(after.powerupMillis().getFirst()==loadout.powerupMillis().getFirst()-8,"Transferred timer did not run");
        returning=after;
      }
      try(var returnFs=Pk3FileSystem.mount(games,"baseq3");var returnSaved=new GameFileStore(out.resolve("return-saved"))) {
        returnSaved.write(new VirtualPath("q3config.cfg"),"seta net_enabled 0\nseta bot_enable 0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var world=BspSceneBuilder.build("maps/q3dm17.bsp",BspReader.read(returnFs.read(new VirtualPath("maps/q3dm17.bsp"))),8);
        var materials=MaterialLibrary.load(returnFs,world);
        try {new QuakeSession(returnFs,returnSaved,new Audio(),world,materials,"Return QA",640,480,null,null,returning,new byte[]{1});throw new AssertionError("Wrong return module accepted");}
        catch(IllegalArgumentException expected){}
        try(var resumed=new QuakeSession(returnFs,returnSaved,new Audio(),world,materials,"Return QA",640,480,null,null,returning,module)) {
          check(returning.equals(PlayerLoadout.capture(resumed.server().playerState(0),resumed.server().time())),"Return session changed carried inventory");
          check(resumed.cvars().integer("sv_cheats")==0,"Return left cheats enabled");
          resumed.input().key(178,true,resumed.inputTime());resumed.advance(16,640,480);resumed.input().key(178,false,resumed.inputTime());
          var after=PlayerLoadout.capture(resumed.server().playerState(0),resumed.server().time());
          check(after.ammo().get(5)==returning.ammo().get(5)-1,"Returned original cgame weapon did not fire");
          check(resumed.bridgeLoadout(module).ammo().get(5).equals(after.ammo().get(5)),"Re-export lost updated inventory");
          resumed.command("map q3dm1");resumed.advance(16,640,480);
          var fresh=PlayerLoadout.capture(resumed.server().playerState(0),resumed.server().time());
          check(fresh.weapons()==6&&fresh.armor()==0,"Later map replayed carried inventory");
        }
      }
      source.command("kill");source.advance(16,640,480);reject(source,module);
      String result="PASS standalone-transfer menu/dead/module rejection read-only-export single-command-delivery exact-inventory original-rocket timed-powerups return-map cgame-fire module-rejection re-export fresh-next-map";
      Files.writeString(out.resolve("bridge-transfer-result.txt"),result+"\n");System.out.println(result);
    }
  }
}
