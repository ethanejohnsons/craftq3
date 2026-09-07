import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.server.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Original qagame computes hits and damage against host-sized actors. */
class AuditBridgeCombat {
 static int health(Q3Server game,int slot){return ByteBuffer.wrap(game.playerState(slot)).order(ByteOrder.LITTLE_ENDIAN).getInt(184);}
 public static void main(String[] args)throws Exception {
  boolean[] wall={false};var ground=new BoxTraceWorld(new Vec3(-10000,-10000,-32),new Vec3(10000,10000,0),1,0,1022);
  var cover=new BoxTraceWorld(new Vec3(80,-64,0),new Vec3(96,64,160),1,0,1022);
  var backstop=new BoxTraceWorld(new Vec3(210,-64,0),new Vec3(228,64,160),1,0,1022);
  TraceWorld world=new TraceWorld(){public TraceResult trace(TraceRequest r){return new CompositeTraceWorld(wall[0]?List.of(ground,cover,backstop):List.of(ground,backstop)).trace(r);}public int pointContents(Vec3 p,int m,int i){return ground.pointContents(p,m,i);}};
  var vars=new CvarSystem();vars.register("sv_cheats","1",CvarSystem.ROM|CvarSystem.SYSTEMINFO);vars.register("bot_enable","0",0);vars.register("sv_maxclients","8",0);
  try(var fs=Pk3FileSystem.mount(Path.of(args[0]),"baseq3");var game=new Q3Server(fs,"craftq3_bridge",ExternalWorld.combat(world,new Vec3(0,0,25),0),null,System.out::print,Clock.systemUTC(),null,vars)) {
   game.initialize(1000,42);game.connect(0,Map.of("name","Attacker","model","sarge/default","ip","localhost"));
   int before=health(game,0);var bounds=new BspMap.Bounds(new Vec3(145,-15,0),new Vec3(175,15,64));game.externalActor(1,bounds,100,"Iron Golem");
   name(game,"Iron Golem");
   game.externalActor(1,bounds,100,"Guardian Alex");name(game,"Guardian Alex");
   int generation=game.configstrings().generation();game.externalActor(1,bounds,100,"Guardian Alex");if(game.configstrings().generation()!=generation)throw new AssertionError("Unchanged name rebroadcast");
   game.externalActor(1,bounds,100,"A".repeat(100));name(game,"A".repeat(34));
   game.externalActor(1,bounds,100,"Guardian Alex");
   game.runFrame(game.time()+8);if(health(game,0)<before-1||health(game,1)!=100)throw new AssertionError("Spawn/health sync failed "+health(game,0)+" "+health(game,1));
   if(game.entityStates(0).stream().anyMatch(e->ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN).getInt()==1))throw new AssertionError("Proxy model exposed");
   wall[0]=true;shoot(game,30);int covered=health(game,1);if(covered!=100)throw new AssertionError("Wall did not block damage "+covered);
   if(value(game.playerState(1),32)!=0||value(game.playerState(1),36)!=0||value(game.playerState(1),40)!=0)throw new AssertionError("Covered bullet caused impulse");
   wall[0]=false;shoot(game,30);int exposed=health(game,1);if(exposed>=100||exposed<0)throw new AssertionError("No original weapon damage "+exposed);
   if(value(game.playerState(1),32)<=0)throw new AssertionError("Original bullet impulse absent");
   game.externalActor(1,bounds,50,"Guardian Alex");
   if(value(game.playerState(1),32)!=0||value(game.playerState(1),36)!=0||value(game.playerState(1),40)!=0)throw new AssertionError("Proxy refresh retained old impulse");name(game,"Guardian Alex");game.runFrame(game.time()+8);if(health(game,1)!=50)throw new AssertionError("Host health not mirrored");
   game.externalActor(1,bounds,100);game.clientCommand(0,"give all");
   shoot(game,180,5);int rocket=health(game,1);if(rocket>=100)throw new AssertionError("No rocket damage");
   if(value(game.playerState(1),32)<=100)throw new AssertionError("Original rocket impulse absent");
   game.removeExternalActor(1);if(game.isConnected(1))throw new AssertionError("Actor not removed");
   // A short host-sized target below the direct rocket path still receives original splash damage.
   var shortBounds=new BspMap.Bounds(new Vec3(145,-15,0),new Vec3(175,15,12));
   game.externalActor(1,shortBounds,100);wall[0]=true;
   shoot(game,180,5);int splash=health(game,1);if(splash!=100)throw new AssertionError("Cover did not stop splash");
   if(value(game.playerState(1),32)!=0||value(game.playerState(1),36)!=0||value(game.playerState(1),40)!=0)throw new AssertionError("Covered splash caused impulse");
   wall[0]=false;shoot(game,180,5);int exposedSplash=health(game,1);if(exposedSplash>=100)throw new AssertionError("No rocket splash on short target");
   if(value(game.playerState(1),32)>=0)throw new AssertionError("Splash impulse must point away from backstop impact, toward shooter");
   game.removeExternalActor(1);game.externalActor(1,bounds,100);
   game.restart(43);if(game.isConnected(1)||!game.isConnected(0)||health(game,0)<=0)throw new AssertionError("External restart cleanup failed");
   game.externalActor(1,bounds,100);if(health(game,0)<=0)throw new AssertionError("Restart admission telefrag");
   int initial=health(game,0);game.externalDamage(25);shoot(game,1);
   if(health(game,0)!=initial-25)throw new AssertionError("Incoming hurt damage failed "+initial+" -> "+health(game,0));
   game.clientCommand(0,"give all");int armored=health(game,0);int armorBefore=ByteBuffer.wrap(game.playerState(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(196);
   game.externalDamage(30);shoot(game,1);int armorAfter=ByteBuffer.wrap(game.playerState(0)).order(ByteOrder.LITTLE_ENDIAN).getInt(196);
   if(health(game,0)>=armored||health(game,0)<=armored-30||armorAfter>=armorBefore)throw new AssertionError("Original armor not applied");
   game.externalDamage(255);game.externalDamage(255);game.externalDamage(255);
   boolean dead=false;for(int i=0;i<100;i++){shoot(game,1);dead|=health(game,0)<=0;}
   if(!dead)throw new AssertionError("Original external death failed");
   shoot(game,250);if(health(game,0)<=0)throw new AssertionError("Original respawn failed");
   game.restart(44);
   byte[] initialState=game.playerState(0);float startX=value(initialState,20),startZ=value(initialState,28);
   game.externalImpulse(new Vec3(180,0,240));
   if(value(game.playerState(0),32)!=180||value(game.playerState(0),40)!=240)throw new AssertionError("Impulse missing from public velocity");
   shoot(game,32);
   if(value(game.playerState(0),20)<startX+10||value(game.playerState(0),28)<startZ+10)throw new AssertionError("Original movement did not integrate impulse");
   game.restart(45);wall[0]=true;game.externalImpulse(new Vec3(1000,0,100));
   shoot(game,100);
   if(value(game.playerState(0),20)>65||value(game.playerState(0),20)<20)throw new AssertionError("Impulse did not stop at host wall");
   var unchanged=game.playerState(0);
   try{game.externalImpulse(new Vec3(65537,0,0));throw new AssertionError("Oversized impulse accepted");}catch(IllegalArgumentException expected){}
   if(!Arrays.equals(unchanged,game.playerState(0)))throw new AssertionError("Invalid impulse changed state");
   game.externalDamage(255);shoot(game,1);if(health(game,0)>0)throw new AssertionError("Death fixture failed");
   unchanged=game.playerState(0);game.externalImpulse(new Vec3(100,100,100));
   if(!Arrays.equals(unchanged,game.playerState(0)))throw new AssertionError("Dead player received impulse");
   System.out.println("\nPASS bridge combat "+game.abi()+" covered="+covered+" exposed="+exposed+" names/rename/health-sync/hidden-model/removal/no-telefrag/restart/incoming/armor/death/respawn/impulse/movement/wall/dead-impulse/outgoing-bullet-rocket-splash-impulse rocket="+rocket+" covered-splash="+splash+" exposed-splash="+exposedSplash+"");
  }
 }
 static float value(byte[] state,int offset){return ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN).getFloat(offset);}
 static void name(Q3Server game,String expected){String raw=game.configstrings().get(545);String actual=dev.bluevista.craftq3.core.cvar.InfoString.parse(raw.startsWith("\\")?raw:"\\"+raw,1024).get("n");if(!expected.equals(actual))throw new AssertionError("Actor name "+actual+" != "+expected);}
 static void shoot(Q3Server game,int frames){shoot(game,frames,2);}
 static void shoot(Q3Server game,int frames,int weapon){for(int i=0;i<frames;i++){int time=game.time()+8;game.userCommand(0,new UserCommand(time,0,0,0,1,weapon,0,0,0));game.runFrame(time);}}
}
