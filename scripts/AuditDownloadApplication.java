import dev.bluevista.craftq3.assets.audio.PcmSound;
import dev.bluevista.craftq3.assets.fs.*;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.fabric.game.QuakeSession;
import dev.bluevista.craftq3.platform.audio.AudioBackend;
import java.nio.file.*;
import java.util.*;

/** Actual Fabric CPU sessions with different pack inventories and automatic private UDP installation. */
class AuditDownloadApplication {
  static class Audio implements AudioBackend {
    int sounds;long voices;
    public int register(String name,PcmSound sound){return ++sounds;}
    public long play(Playback playback){return ++voices;}
    public void updateEntity(int entity,Vec3 origin){}public void beginFrame(){}public void submitLoop(Loop loop){}
    public void endFrame(Listener listener){}public void clearLoops(){}public void stop(long voice){}public void stopAll(){}public void volume(float value){}
    public Diagnostics diagnostics(){return new Diagnostics(true,sounds,0,0,0,voices,0,"Hosted CPU audit");}public void close(){}
  }
  static boolean sawProgress;
  public static void main(String[] args)throws Exception {
    String runtime=System.getProperty("craftq3.audit.runtime");
    if(runtime!=null)for(Class<?> type:List.of(QuakeSession.class,dev.bluevista.craftq3.client.net.Pk3Downloads.class,DownloadCache.class))
      check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).startsWith(Path.of(runtime)),"Audit did not load inspected "+type);
    Path hostGames=Path.of(args[1]).resolve("host"),remoteGames=Path.of(args[1]).resolve("remote"),out=Path.of(args[2]);
    Files.createDirectories(out);Path cachePath=out.resolve("cache");
    try(var hostFs=Pk3FileSystem.mount(hostGames,"baseq3");
        var host=new QuakeSession(hostFs,null,new Audio(),null,null,"Download Host",640,480)) {
      quiet(host);host.cvars().register("sv_allowDownload","1",0);
      hostFs.read(new VirtualPath("models/qa-download/marker.bin"));hostFs.read(new VirtualPath("models/qa-download/second.bin"));
      host.command("set bot_enable 0; map q3dm1");host.advance(16,640,480);
      check(host.hosting(),"Host did not start: "+host.consoleLines());
      var address=host.hostedAddress().orElseThrow();check(address.getAddress().isLoopbackAddress(),"Listener not private");
      String connect="connect 127.0.0.1:"+address.getPort();
      try(var remoteFs=Pk3FileSystem.mount(remoteGames,"baseq3");var cache=new DownloadCache(cachePath);
          var remote=new QuakeSession(remoteFs,null,new Audio(),null,null,"Download Client",640,480,null,cache)) {
        quiet(remote);remote.cvars().set("cl_allowDownload","1",CvarSystem.Source.ENGINE);remote.command(connect);join(host,remote);
        check(sawProgress,"Original download UI cvars never showed progress");check(cache.entries().size()==2,"Expected two verified packs");
        check(remoteFs.read(new VirtualPath("models/qa-download/marker.bin")).length==65537,"Downloaded asset unavailable");
        check(remote.playing()&&host.hostedClients().stream().anyMatch(c->c.active()),"Pure gameplay did not resume");
        remote.command("disconnect");for(int i=0;i<10;i++)step(host,remote);
      }
      // A fresh session must reuse persisted packs with both server/client downloads disabled.
      host.cvars().set("sv_allowDownload","0",CvarSystem.Source.ENGINE);sawProgress=false;
      try(var remoteFs=Pk3FileSystem.mount(remoteGames,"baseq3");var cache=new DownloadCache(cachePath);
          var remote=new QuakeSession(remoteFs,null,new Audio(),null,null,"Cached Client",640,480,null,cache)) {
        quiet(remote);remote.command(connect);join(host,remote);check(!sawProgress,"Cache reuse requested another download");
        remote.command("disconnect");for(int i=0;i<10;i++)step(host,remote);
      }
      // Cancel an in-flight download and verify the UI returns and staging files disappear.
      host.cvars().set("sv_allowDownload","1",CvarSystem.Source.ENGINE);sawProgress=false;
      Path cancelPath=out.resolve("cancel-cache");
      try(var remoteFs=Pk3FileSystem.mount(remoteGames,"baseq3");var cache=new DownloadCache(cancelPath);
          var remote=new QuakeSession(remoteFs,null,new Audio(),null,null,"Cancel Client",640,480,null,cache)) {
        quiet(remote);remote.cvars().set("cl_allowDownload","1",CvarSystem.Source.ENGINE);remote.cvars().set("rate","1000",CvarSystem.Source.ENGINE);remote.command(connect);
        long deadline=System.nanoTime()+15_000_000_000L;while(!sawProgress&&System.nanoTime()<deadline)step(host,remote);
        check(sawProgress,"No cancellable transfer");remote.command("disconnect");for(int i=0;i<10;i++)step(host,remote);
        check(!remote.networked()&&remote.menuVisible()&&cache.entries().isEmpty(),"Cancellation did not clean up");
        try(var files=Files.list(cancelPath)){check(files.count()==1,"Cancellation left staging files");}
      }
      // Corrupt only an authored fixture after host indexing; the advertised CRCs remain unchanged.
      Path fixture=hostGames.resolve("baseq3/zz_download_fixture.pk3");byte[] bytes=Files.readAllBytes(fixture);
      int nameLength=(bytes[26]&255)|((bytes[27]&255)<<8),extraLength=(bytes[28]&255)|((bytes[29]&255)<<8);
      bytes[30+nameLength+extraLength]^=1;Files.write(fixture,bytes);
      Path badPath=out.resolve("bad-cache");sawProgress=false;
      try(var remoteFs=Pk3FileSystem.mount(remoteGames,"baseq3");var cache=new DownloadCache(badPath);
          var remote=new QuakeSession(remoteFs,null,new Audio(),null,null,"Corrupt Client",640,480,null,cache)) {
        quiet(remote);remote.cvars().set("cl_allowDownload","1",CvarSystem.Source.ENGINE);remote.command(connect);
        long deadline=System.nanoTime()+30_000_000_000L;
        do{step(host,remote);}while(System.nanoTime()<deadline&&(!sawProgress||remote.networked()));
        check(sawProgress&&!remote.networked()&&remote.menuVisible(),"Corrupt transfer did not return to menu: "+remote.consoleLines());
        check(remote.consoleLines().stream().anyMatch(line->line.contains("Downloaded PK3 member CRC/length mismatch")),"Missing verification diagnostic: "+remote.consoleLines());
        check(cache.entries().isEmpty(),"Corrupt pack published");try(var files=Files.list(badPath)){check(files.count()==1,"Failed verification left staging files");}
      }
      String result="PASS download application ui="+host.ui().apiVersion()+" two-packs/pure-resume/cache-reuse/cancellation/corruption-rejected";
      Files.writeString(out.resolve("download-application-result.txt"),result+"\n");System.out.println(result);
    }
  }
  static void quiet(QuakeSession session){for(int i=1;i<=5;i++)session.cvars().set("sv_master"+i,"",CvarSystem.Source.ENGINE);}
  static void join(QuakeSession host,QuakeSession remote)throws Exception {
    long deadline=System.nanoTime()+40_000_000_000L;
    do{step(host,remote);if(remote.playing()&&remote.networked()&&host.hostedClients().stream().anyMatch(c->c.active()))return;}while(System.nanoTime()<deadline);
    throw new AssertionError("Join failed: "+remote.consoleLines()+" HOST "+host.consoleLines());
  }
  static void step(QuakeSession host,QuakeSession remote)throws Exception {
    host.advance(16,640,480);remote.advance(16,640,480);
    sawProgress|=!remote.cvars().string("cl_downloadName").isEmpty();Thread.sleep(16);
  }
  static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
}
