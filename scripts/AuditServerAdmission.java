import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.*;
import dev.bluevista.craftq3.server.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Original qagame verifies delayed remote admission, restart, two players and slot reuse. */
class AuditServerAdmission {
  public static void main(String[] args)throws Exception {
    try(var disk=Pk3FileSystem.mount(Path.of(args[0]),"baseq3")) {
      VirtualFileSystem fs=new VirtualFileSystem(){
        public Optional<Origin> which(VirtualPath p){return disk.which(p);}
        public List<VirtualPath> list(String p){return disk.list(p);}
        public List<Origin> searchOrder(){return disk.searchOrder();}
        public byte[] read(VirtualPath p)throws java.io.IOException{return args.length>1&&p.value().equals("vm/qagame.qvm")?Files.readAllBytes(Path.of(args[1])):disk.read(p);}
        public void close(){}
      };
      var cvars=new CvarSystem();cvars.register("sv_cheats","0",CvarSystem.ROM|CvarSystem.SYSTEMINFO);
      cvars.register("bot_enable","0",0);cvars.register("sv_maxclients","4",0);
      var lines=new ArrayList<String>();
      try(var server=new Q3Server(fs,"q3dm1",BspReader.read(fs.read(new VirtualPath("maps/q3dm1.bsp"))),null,text->{lines.add(text);System.out.print(text);},Clock.systemUTC(),null,cvars)) {
        server.initialize(1000,42);
        server.connectPending(1,info("First"));
        check(server.isConnected(1)&&!server.hasEntered(1),"Admission entered too early");
        try{server.userCommand(1,UserCommand.idle(server.time()));throw new AssertionError("Pending client thought");}catch(IllegalStateException expected){}
        for(int i=0;i<4;i++)server.runFrame(server.time()+50);
        check(server.commandsSince(0).stream().noneMatch(c->c.text().contains("entered the game")),"Original game entered before first command");
        server.begin(1,UserCommand.idle(server.time()));
        check(server.hasEntered(1),"First client did not enter");
        check(server.commandsSince(0).stream().anyMatch(c->c.text().contains("First")&&c.text().contains("entered the game")),"Original first-entry announcement missing");
        server.connectPending(2,info("Second"));
        server.restart(43);
        check(server.hasEntered(1)&&server.isConnected(2)&&!server.hasEntered(2),"Restart admitted a pending peer");
        check(server.commandsSince(0).stream().noneMatch(c->c.text().contains("Second")&&c.text().contains("entered the game")),"Original restart entered pending player");
        server.begin(2,UserCommand.idle(server.time()));
        double[][] start={position(server,1),position(server,2)}; double[] moved=new double[2];
        for(int frame=0;frame<60;frame++){
          int time=server.time()+50;
          for(int client=1;client<=2;client++)server.userCommand(client,new UserCommand(time,0,(frame/15)*16384,0,0,0,127,0,0));
          server.runFrame(time);
          for(int client=1;client<=2;client++) moved[client-1]=Math.max(moved[client-1],distance(start[client-1],position(server,client)));
          check(server.entitySnapshot(1).visibleEntities()>0&&server.entitySnapshot(2).visibleEntities()>0,"Player snapshots missing");
        }
        System.out.println("MOVEMENT "+Arrays.toString(moved)+" start "+Arrays.deepToString(start)+" end "+Arrays.toString(position(server,1))+" / "+Arrays.toString(position(server,2)));
        check(moved[0]>10&&moved[1]>10,"Both original players must move");
        server.disconnect(1,"audit leave");check(!server.isConnected(1)&&!server.hasEntered(1),"Slot remained live");
        server.connectPending(1,info("Replacement"));check(!server.hasEntered(1),"Reused slot entered early");
        server.begin(1,UserCommand.idle(server.time()));check(server.hasEntered(1),"Reused slot failed admission");
        System.out.println("PASS "+server.abi()+": pending admission, first command, retained/pending restart, 60 two-player frames, disconnect/reuse");
      }
    }
  }
  static Map<String,String> info(String name){return Map.of("name",name,"ip","127.0.0.1","model","sarge/default","handicap","100","rate","25000","snaps","20");}
  static double[] position(Q3Server s,int client){var b=ByteBuffer.wrap(s.playerState(client)).order(ByteOrder.LITTLE_ENDIAN);return new double[]{b.getFloat(20),b.getFloat(24),b.getFloat(28)};}
  static double distance(double[] a,double[] b){double d=0;for(int i=0;i<2;i++)d+=(a[i]-b[i])*(a[i]-b[i]);return Math.sqrt(d);}
  static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
}
