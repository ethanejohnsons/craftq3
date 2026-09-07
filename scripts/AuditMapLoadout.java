import dev.bluevista.craftq3.assets.bsp.*;
import dev.bluevista.craftq3.assets.fs.*;
import dev.bluevista.craftq3.core.cvar.*;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.server.*;
import java.nio.*;import java.nio.file.*;import java.time.*;import java.util.*;

/** Original map geometry and game damage after an explicit local inventory admission. */
class AuditMapLoadout {
 static int value(Q3Server game,int client,int offset){return ByteBuffer.wrap(game.playerState(client)).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);}
 static float number(Q3Server game,int client,int offset){return Float.intBitsToFloat(value(game,client,offset));}
 static BspMap fixture(BspMap m){return new BspMap(List.of(Map.of("classname","worldspawn"),Map.of("classname","info_player_deathmatch","origin","0 0 2000"),Map.of("classname","info_player_deathmatch","origin","128 0 2000")),m.textures(),m.planes(),m.nodes(),m.leaves(),m.leafFaces(),m.leafBrushes(),m.models(),m.brushes(),m.brushSides(),m.vertices(),m.meshVertices(),m.effects(),m.faces(),m.lightmaps(),m.lightVolumes(),m.visibility());}
 public static void main(String[] args)throws Exception {
  for(int health:List.of(1,73,100,187,200)) {
   var vars=new CvarSystem();vars.register("sv_cheats","0",CvarSystem.ROM|CvarSystem.SYSTEMINFO);vars.register("bot_enable","0",0);vars.register("sv_maxclients","2",0);
   var ammo=new ArrayList<Integer>(Collections.nCopies(16,0));ammo.set(1,-1);ammo.set(2,13);ammo.set(5,7);
   var wanted=new PlayerLoadout(health,health==1?0:57,38,5,0,ammo,List.of(24000,0,0,0,0,0));
   try(var fs=Pk3FileSystem.mount(Path.of(args[0]),"baseq3")) {
    var map=fixture(BspReader.read(fs.read(new VirtualPath("maps/q3dm17.bsp"))));
    try(var game=new Q3Server(fs,"maps/q3dm17.bsp",map,null,x->{},Clock.systemUTC(),null,vars,wanted)) {
     game.initialize(1000,42);game.connect(0,Map.of("name","Arriving QA","ip","localhost"));
     if(!wanted.equals(PlayerLoadout.capture(game.playerState(0),game.time())))throw new AssertionError("Inventory mismatch: "+PlayerLoadout.capture(game.playerState(0),game.time()));
     if(vars.integer("sv_cheats")!=0)throw new AssertionError("Admission enabled cheats");
     byte[] before=game.playerState(0);game.clientCommand(0,"give all");
     if(!Arrays.equals(before,game.playerState(0)))throw new AssertionError("Original give still permitted");
     try{game.restoreLoadout(wanted);throw new AssertionError("Admission replayed");}catch(IllegalStateException expected){}
     try{game.externalDamage(1);throw new AssertionError("Local match exposed bridge damage API");}catch(IllegalArgumentException expected){}
     game.connect(1,Map.of("name","Shooter QA","ip","localhost"));
     for(int i=0;i<30;i++){int tick=game.time()+16;game.userCommand(0,new UserCommand(tick,0,0,0,0,5,0,0,0));game.userCommand(1,new UserCommand(tick,0,0,0,0,2,0,0,0));game.runFrame(tick);}
     double yaw=Math.toDegrees(Math.atan2(number(game,0,24)-number(game,1,24),number(game,0,20)-number(game,1,20)));
     double distance=Math.hypot(number(game,0,20)-number(game,1,20),number(game,0,24)-number(game,1,24));
     double pitch=Math.toDegrees(Math.atan2(number(game,1,28)+26-number(game,0,28)-8,distance));
     int now=game.time()+8;
     game.userCommand(0,new UserCommand(now,0,0,0,0,5,0,0,0));
     game.userCommand(1,new UserCommand(now,(int)Math.round(pitch*65536/360)-value(game,1,56),(int)Math.round(yaw*65536/360)-value(game,1,60),0,1,2,0,0,0));game.runFrame(now);
     System.out.println("shot state="+value(game,1,4)+" weapon="+value(game,1,144)+" ammo="+value(game,1,384)+" aim="+number(game,1,152)+","+number(game,1,156)+" target="+number(game,0,20)+","+number(game,0,24)+","+number(game,0,28)+" shooter="+number(game,1,20)+","+number(game,1,24)+","+number(game,1,28));
     int expectedHealth=health-(health==1?7:2),expectedArmor=health==1?0:52;
     if(value(game,0,184)!=expectedHealth||value(game,0,196)!=expectedArmor)throw new AssertionError("Private health/original bullet/armor differs: initial="+health+" final="+value(game,0,184)+" armor="+value(game,0,196));
    }
   }
  }
  System.out.println("PASS map admission health=1/73/100/187/200 exact-inventory cheats-restored no-replay original-bullet armor private-health low-health-death");
 }
}
