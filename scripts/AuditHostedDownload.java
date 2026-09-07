import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.net.RemoteConnection;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.platform.net.DatagramListener;
import dev.bluevista.craftq3.server.*;
import dev.bluevista.craftq3.server.net.Protocol68Host;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.zip.*;

/** Original qagame, authored PK3 fixture and actual private UDP download/ack/resume. */
class AuditHostedDownload {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),base=root.resolve("baseq3");Files.createDirectories(base);
  Path archive=base.resolve("zz_download_fixture.pk3");byte[] marker=new byte[65537];new Random(42).nextBytes(marker);
  try(var zip=new ZipOutputStream(Files.newOutputStream(archive,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE))){zip.putNextEntry(new ZipEntry("models/qa-download/marker.bin"));zip.write(marker);zip.closeEntry();}
  byte[] expected=Files.readAllBytes(archive);
  try(var fs=Pk3FileSystem.mount(root,"baseq3");var udp=new DatagramListener(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),0))) {
   fs.read(new VirtualPath("models/qa-download/marker.bin"));
   var cvars=new CvarSystem();cvars.register("sv_cheats","0",CvarSystem.ROM|CvarSystem.SYSTEMINFO);cvars.register("bot_enable","0",0);cvars.register("sv_maxclients","4",0);cvars.register("sv_allowDownload","1",0);
   try(var game=new Q3Server(fs,"q3dm1",BspReader.read(fs.read(new VirtualPath("maps/q3dm1.bsp"))),null,System.out::print,Clock.systemUTC(),null,cvars)) {
    game.initialize(1000,42);
    try(var host=new Protocol68Host(game,fs,12345,0,System.out::println);var remote=RemoteConnection.open(udp.localAddress(),Map.of("name","Download QA","model","sarge/default","rate","25000","snaps","20"),42,31001,0)) {
     var sink=new ByteArrayOutputStream();try(var receiver=new DownloadReceiver(sink)) {
      boolean requested=false,done=false,pure=false,denied=false,errorRequested=false;int generation=0,initialGeneration=0,blocks=0,dropped=0,sent=0;
      for(int frame=0;frame<6000&&!denied;frame++) {
       long now=frame*16L;var result=remote.pump(now);var wire=remote.session().orElse(null);
       if(wire!=null&&wire.gameState().isPresent()) {
        generation=wire.gameStateMessageSequence();
        if(!requested){wire.beginDownload();wire.command("download baseq3/zz_download_fixture.pk3");requested=true;initialGeneration=generation;}
        for(var event:result.events())if(event.kind()==RemoteConnection.EventKind.MESSAGE)
         for(var operation:event.received().message().operations())if(operation instanceof ServerMessageCodec.Download packet) {
          if(errorRequested){check(packet.error()!=null&&packet.error().contains("Official"),"Official PK3 was not refused");denied=true;continue;}
          var progress=receiver.accept(packet);
          if(progress.accepted()){blocks++;wire.command("nextdl "+progress.acknowledge());}
          if(progress.complete()){check(Arrays.equals(expected,sink.toByteArray()),"Transferred PK3 differs");done=true;wire.command("donedl");}
         }
        if(done&&generation!=initialGeneration) {
         if(!pure){fs.read(new VirtualPath("vm/cgame.qvm"));fs.read(new VirtualPath("vm/ui.qvm"));wire.command("cp 12345 "+fs.referencedPureChecksums());pure=true;}
         remote.userCommand(UserCommand.idle(game.time()));
         if(!errorRequested&&host.clients().stream().anyMatch(Protocol68Host.Client::active)){
          wire.beginDownload();wire.command("download baseq3/pak0.pk3");errorRequested=true;
         }
        }
       }
       for(int i=0;i<64;i++){var packet=udp.poll();if(packet.isEmpty())break;var p=packet.orElseThrow();host.receive(p.peer(),p.payload(),now);}
       if(frame%3==0)game.runFrame(game.time()+50);host.tick(now);
       for(int i=0;i<128;i++){var packet=host.peekPacket();if(packet.isEmpty())break;var p=packet.orElseThrow();if(frame>50&&!done&&++sent%7==0){host.packetSent();dropped++;continue;}if(!udp.send(p.peer(),p.payload()))break;host.packetSent();}
      }
      check(done&&pure&&generation!=initialGeneration&&denied&&dropped>0,"Download/resume incomplete: "+remote.state()+" "+remote.diagnostic()+" clients "+host.clients()+" bytes "+sink.size());
      System.out.println("\nPASS hosted download "+game.abi()+" bytes="+expected.length+" blocks="+blocks+" dropped="+dropped+" resume/pure/official-refusal");
     }
    }
   }
  } finally {Files.deleteIfExists(archive);}
 }
 static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
}
