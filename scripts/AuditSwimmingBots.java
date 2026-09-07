import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.collision.BspTraceWorld;
import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.server.Q3Server;
import java.nio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Opt-in original qagame match: counts actual bot positions inside BSP liquids. */
class AuditSwimmingBots {
  public static void main(String[] args)throws Exception {
    if(args.length!=2)throw new IllegalArgumentException("AuditSwimmingBots <games-root> <map>");
    try(var files=Pk3FileSystem.mount(Path.of(args[0]),"baseq3")){
      var bsp=BspReader.read(files.read(new VirtualPath("maps/"+args[1]+".bsp")));
      var world=new BspTraceWorld(bsp);
      try(var server=new Q3Server(files,args[1],bsp,null,System.out::print,Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"),ZoneOffset.UTC))){
        server.initialize(1000,42);
        server.connect(0,Map.of("name","WaterObserver","model","sarge"));
        for(String name:new String[]{"sarge","visor","mynx"})if(!server.consoleCommand(CommandParser.tokenize("addbot "+name+" 3")))throw new AssertionError("addbot rejected");
        int[] water=new int[8],frames=new int[8];double[] distance=new double[8];Vec3[] previous=new Vec3[8];
        int start=server.time();
        for(int time=start+50;time<=start+300000;time+=50){
          server.runFrame(time);
          for(int client=0;client<8;client++)if(server.isBot(client)){
            var state=ByteBuffer.wrap(server.playerState(client)).order(ByteOrder.LITTLE_ENDIAN);
            var p=new Vec3(state.getFloat(20),state.getFloat(24),state.getFloat(28));
            frames[client]++;
            if((world.pointContents(new Vec3(p.x(),p.y(),(float)p.z()-2),56,-1)&56)!=0)water[client]++;
            if(previous[client]!=null)distance[client]+=Math.hypot(p.x()-previous[client].x(),p.y()-previous[client].y());
            previous[client]=p;
          }
        }
        int total=Arrays.stream(water).sum();
        for(int client=0;client<8;client++)if(frames[client]>0)System.out.println("SWIMMING_BOT map="+args[1]+" client="+client+" frames="+frames[client]+" liquidFrames="+water[client]+" horizontalDistance="+distance[client]);
        System.out.println("SWIMMING_MATCH map="+args[1]+" duration=300000 totalLiquidFrames="+total+" botlib="+server.botlibStatus());
        if(total==0)throw new AssertionError("No original bot actually entered liquid");
      }
    }
  }
}
