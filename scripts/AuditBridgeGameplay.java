import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Client;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Both original QVMs on mutable host block geometry, without a BSP world asset. */
class AuditBridgeGameplay {
  static class Audio implements AudioBackend {
    int sounds;long voices;
    public int register(String name,PcmSound sound){return ++sounds;}
    public long play(Playback playback){return ++voices;}
    public void updateEntity(int entity,Vec3 origin){}public void beginFrame(){}public void submitLoop(Loop loop){}
    public void endFrame(Listener listener){}public void clearLoops(){}public void stop(long voice){}public void stopAll(){}public void volume(float value){}
    public Diagnostics diagnostics(){return new Diagnostics(true,sounds,0,0,0,voices,0,"Hosted CPU audit");}public void close(){}
  }
  static boolean wall=true;
  public static void main(String[] args)throws Exception {
    var grid=new GridTraceWorld(32,cell->{
      if(cell.z()==-1||wall&&cell.x()==5&&cell.z()>=0&&cell.z()<4)
        return List.of(new BoxTraceWorld(new Vec3(cell.x()*32,cell.y()*32,cell.z()*32),new Vec3((cell.x()+1)*32,(cell.y()+1)*32,(cell.z()+1)*32),1,0,1022));
      return List.of();
    });
    var cvars=new CvarSystem();cvars.register("sv_cheats","1",CvarSystem.ROM|CvarSystem.SYSTEMINFO);cvars.register("bot_enable","0",0);
    var world=ExternalWorld.combat(grid,new Vec3(16,16,25),0);
    try(var fs=Pk3FileSystem.mount(Path.of(args[0]),"baseq3");var game=new Q3Server(fs,"craftq3_bridge",world,null,System.out::print,Clock.systemUTC(),null,cvars)) {
      game.initialize(1000,42);game.connect(0,Map.of("name","Bridge QA","model","sarge/default","ip","localhost"));
      try(var client=new Q3Client(fs,game,f->{},new Audio(),System.out::print)) {
        client.initialize(0,640,480);client.frame(game.time(),640,480);
        int views=0,quads=0;double maxZ=0;Vec3 blocked=null;
        for(int step=0;step<100;step++) {
          if(step==45){blocked=origin(game.playerState(0));wall=false;}
          int time=game.time()+50;
          client.userCommand(new UserCommand(time,0,0,0,1,client.selectedWeapon(),127,0,step==65?127:0));game.runFrame(time);
          var frame=client.frame(time,640,480);views+=frame.commands().stream().filter(CgameFrame.View.class::isInstance).count();quads+=frame.commands().stream().filter(CgameFrame.Quad.class::isInstance).count();
          maxZ=Math.max(maxZ,origin(game.playerState(0)).z());
        }
        var end=origin(game.playerState(0));
        if(game.playerBounds(0).min().z()<0||game.playerBounds(0).min().z()>.2)
          throw new AssertionError("Quake body feet no longer rest on host floor: "+game.playerBounds(0));
        if(blocked==null||blocked.x()>145.1||blocked.x()<130||end.x()<400||maxZ<55||views<100||quads<100)
          throw new AssertionError("Bridge movement failed blocked="+blocked+" end="+end+" jump="+maxZ+" views="+views+" quads="+quads);
        for(int up : new int[]{-127,0}) {
          for(int n=0;n<12;n++) {
            int now=game.time()+8;
            client.userCommand(new UserCommand(now,0,0,0,0,client.selectedWeapon(),0,0,up));
            game.runFrame(now);client.frame(now,640,480);
          }
          var hull=game.playerBounds(0);
          int eye=ByteBuffer.wrap(game.playerState(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(164);
          if(Math.abs(hull.max().x()-hull.min().x()-30)>1e-4
              ||Math.abs(hull.max().z()-hull.min().z()-(up<0?40:56))>1e-4
              ||eye!=(up<0?12:26))
            throw new AssertionError("Original standing/crouching hull mismatch: "+hull+" eye="+eye);
        }
        System.out.println("\nPASS bridge "+game.abi()+" wall="+blocked+" after-removal="+end+" jump="+maxZ+" views="+views+" quads="+quads+" standing-crouch-bounds=true eye-height=true");
      }
    }
  }
  static Vec3 origin(byte[] bytes){var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);return new Vec3(b.getFloat(20),b.getFloat(24),b.getFloat(28));}
}
