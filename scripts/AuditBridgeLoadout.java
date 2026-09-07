import dev.bluevista.craftq3.assets.fs.*;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.cvar.*;
import dev.bluevista.craftq3.core.math.*;
import dev.bluevista.craftq3.server.*;
import java.nio.*;import java.nio.file.*;import java.time.*;import java.util.*;

/** Health restoration must remain synchronized with subsequent original game damage. */
class AuditBridgeLoadout {
 static int value(Q3Server game,int offset){return ByteBuffer.wrap(game.playerState(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);}
 public static void main(String[] args)throws Exception {
  for(int health:List.of(1,73,187,200)) {
   var vars=new CvarSystem();vars.register("sv_cheats","1",CvarSystem.ROM|CvarSystem.SYSTEMINFO);vars.register("bot_enable","0",0);vars.register("sv_maxclients","2",0);
   var ground=new BoxTraceWorld(new Vec3(-10000,-10000,-32),new Vec3(10000,10000,0),1,0,1022);
   try(var fs=Pk3FileSystem.mount(Path.of(args[0]),"baseq3");var game=new Q3Server(fs,"craftq3_bridge",ExternalWorld.combat(ground,new Vec3(0,0,25),0),null,x->{},Clock.systemUTC(),null,vars)) {
    game.initialize(1000,42);game.connect(0,Map.of("name","Loadout QA","model","sarge/default","ip","localhost"));
    game.clientCommand(0,"give Medkit");int holdable=value(game,188);if(holdable<=0)throw new AssertionError("Original medkit missing");
    var ammo=new ArrayList<Integer>(Collections.nCopies(16,0));ammo.set(1,-1);ammo.set(2,13);ammo.set(5,7);
    var wanted=new PlayerLoadout(health,health==1?0:57,(1<<1)|(1<<2)|(1<<5),5,holdable,ammo,List.of(24000,0,0,0,0,0));
    game.restoreLoadout(wanted);
    if(!wanted.equals(PlayerLoadout.capture(game.playerState(0),game.time())))throw new AssertionError("Restored inventory mismatch: "+PlayerLoadout.capture(game.playerState(0),game.time()));
    int now=game.time()+8;game.userCommand(0,new UserCommand(now,0,0,0,1,5,0,0,0));game.runFrame(now);
    if(value(game,396)!=6)throw new AssertionError("Original rocket did not consume restored ammo");
    if(value(game,316)-game.time()!=23992)throw new AssertionError("Timed powerup did not advance");
    game.externalDamage(health==1?1:30);now=game.time()+8;game.userCommand(0,UserCommand.idle(now));game.runFrame(now);
    if(health==1) {if(value(game,184)>0)throw new AssertionError("Restored low health did not die");}
    else if(value(game,184)!=health-10||value(game,196)!=37)throw new AssertionError("Private/public health or original armor diverged: "+value(game,184)+"/"+value(game,196));
    if(health==73){now=game.time()+8;game.userCommand(0,new UserCommand(now,0,0,0,4,5,0,0,0));game.runFrame(now);if(value(game,188)!=0||value(game,184)!=(game.abi()==GameAbi.RETAIL_1999?100:125))throw new AssertionError("Original restored medkit failed: held="+value(game,188)+" health="+value(game,184));}
   }
  }
  System.out.println("PASS bridge loadout health=1/73/187/200 exact-inventory=true original-rocket=true armor=true death=true timed-powerups=true original-medkit=true");
 }
}
