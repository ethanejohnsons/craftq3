import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.Q3Client;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import dev.bluevista.craftq3.render.CgameFrame;
import dev.bluevista.craftq3.server.*;
import java.nio.*;import java.nio.file.*;import java.time.Clock;import java.util.*;

/** Original item rendering, touch, quantities and respawn on external terrain. */
class AuditBridgePickups {
  static class Audio implements AudioBackend {
    int sounds;long voices;
    public int register(String name,PcmSound sound){return ++sounds;}
    public long play(Playback playback){return ++voices;}
    public void updateEntity(int entity,Vec3 origin){}public void beginFrame(){}public void submitLoop(Loop loop){}
    public void endFrame(Listener listener){}public void clearLoops(){}public void stop(long voice){}public void stopAll(){}public void volume(float value){}
    public Diagnostics diagnostics(){return new Diagnostics(true,sounds,0,0,0,voices,0,"Hosted CPU audit");}public void close(){}
  }

  static int value(Q3Server game,int offset){return ByteBuffer.wrap(game.playerState(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);}
  static float x(Q3Server game){return Float.intBitsToFloat(value(game,20));}
  static CgameFrame step(Q3Server game,Q3Client client,int forward,int fire){
    int time=game.time()+16;client.userCommand(new UserCommand(time,fire==0?0:-16384,0,0,fire,client.selectedWeapon(),forward,0,0));game.runFrame(time);return client.frame(time,640,480);
  }
  static void check(boolean okay,String reason){if(!okay)throw new AssertionError(reason);}
  public static void main(String[] args)throws Exception {
    var cvars=new CvarSystem();cvars.register("sv_cheats","1",CvarSystem.ROM|CvarSystem.SYSTEMINFO);cvars.register("bot_enable","0",0);
    var ground=new BoxTraceWorld(new Vec3(-10000,-10000,-32),new Vec3(10000,10000,0),1,0,1022);
    var world=ExternalWorld.combat(ground,new Vec3(0,0,24),0,List.of(
        new ExternalPickup("weapon_rocketlauncher",new Vec3(160,0,32)),
        new ExternalPickup("ammo_rockets",new Vec3(320,0,32)),
        new ExternalPickup("item_armor_combat",new Vec3(480,0,32)),
        new ExternalPickup("item_health_large",new Vec3(640,0,32))));
    try(var fs=Pk3FileSystem.mount(Path.of(args[0]),"baseq3");var game=new Q3Server(fs,"craftq3_bridge",world,null,x->{},Clock.systemUTC(),null,cvars)) {
      game.initialize(1000,42);game.connect(0,Map.of("name","Pickup QA","ip","localhost","model","sarge/default"));
      try(var client=new Q3Client(fs,game,f->{},new Audio(),x->{})) {
        client.initialize(0,640,480);client.frame(game.time(),640,480);
        CgameFrame initial=null;for(int i=0;i<30;i++)initial=step(game,client,0,0);
        boolean rendered=initial.commands().stream().filter(CgameFrame.View.class::isInstance).map(CgameFrame.View.class::cast)
            .flatMap(v->v.entities().stream()).anyMatch(e->e.model()!=null&&e.model().name().contains("rocketl"));
        check(rendered&&(value(game,192)&32)==0,"Original world pickup was not drawn before ownership");
        game.externalDamage(70);step(game,client,0,0);int hurt=value(game,184);
        int weaponAmmo=0;int ticks=0;
        while(x(game)<700&&ticks++<1000){step(game,client,127,0);if(weaponAmmo==0&&(value(game,192)&32)!=0)weaponAmmo=value(game,396);}
        int first=value(game,396);
        check(weaponAmmo>0&&first>weaponAmmo&&value(game,196)>0&&value(game,184)>hurt,"Original touch/weapon/ammo/armor/health failed: "+PlayerLoadout.capture(game.playerState(0),game.time()));
        while(game.time()<8000)step(game,client,0,0);
        ticks=0;while(x(game)>160&&ticks++<1000)step(game,client,-127,0);
        int replenished=value(game,396);check(replenished>first,"Original weapon did not respawn: "+first+" -> "+replenished);
        client.consoleCommand(dev.bluevista.craftq3.core.command.CommandParser.tokenize("weapon 5"));
        for(int i=0;i<100&&(i==0||value(game,144)!=5||value(game,148)!=0||value(game,44)>0);i++)step(game,client,0,0);
        step(game,client,0,1);check(value(game,396)==replenished-1,"Collected original rocket did not fire: selected="+client.selectedWeapon()+" ammo="+value(game,396)+" before="+replenished);
        System.out.println("PASS bridge pickups original-model=true weapon-ammo="+weaponAmmo+" ammo-pickup="+first+" respawn="+replenished+" armor-health=true original-rocket=true");
      }
    }
  }
}
