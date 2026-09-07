import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.platform.net.DatagramListener;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.net.Protocol68Host;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Real loopback UDP with unchanged native established-client wire operations and original qagame. */
class AuditHostedNativeClient {
  static class Native implements AutoCloseable {
    final Process process;final BufferedReader input;final BufferedWriter output;
    String[] state;final List<byte[]> packets=new ArrayList<>();
    Native(Path probe)throws IOException {
      process=new ProcessBuilder(probe.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      input=process.inputReader(StandardCharsets.US_ASCII);output=process.outputWriter(StandardCharsets.US_ASCII);
    }
    void call(String command)throws IOException {
      output.write(command);output.newLine();output.flush();
      for(String line;(line=input.readLine())!=null;) {
        if(line.equals("END"))return;
        if(line.startsWith("packet "))packets.add(HexFormat.of().parseHex(line.substring(7)));
        if(line.startsWith("STATE "))state=line.split(" ");
      }
      throw new IOException("Native client ended during "+command.substring(0,Math.min(50,command.length())));
    }
    int value(int index){return Integer.parseInt(state[index]);}
    void command(String text)throws IOException{call("command "+HexFormat.of().formatHex(text.getBytes(StandardCharsets.ISO_8859_1)));}
    public void close()throws IOException{try{output.close();}finally{try{input.close();}finally{process.destroy();}}}
  }
  public static void main(String[] args)throws Exception {
    var loop=InetAddress.getByAddress(new byte[]{127,0,0,1});
    try(var fs=Pk3FileSystem.mount(Path.of(args[0]),"baseq3");
        var udp=new DatagramListener(new InetSocketAddress(loop,0));
        var peer=new DatagramListener(new InetSocketAddress(loop,0));
        var nativeClient=new Native(Path.of(args[1]))) {
      var cvars=new CvarSystem();cvars.register("sv_cheats","0",CvarSystem.ROM|CvarSystem.SYSTEMINFO);
      cvars.register("bot_enable","0",0);cvars.register("sv_maxclients","4",0);
      Q3Server game=game(fs,cvars,"q3dm1");
      try(var host=new Protocol68Host(game,fs,12345,0,System.out::println)) {
        host.receive(peer.localAddress(),ConnectionlessPacket.text("getchallenge 42"),0);
        String challenge=ConnectionlessMessage.parse(host.peekPacket().orElseThrow().payload()).line().split(" ")[1];host.packetSent();
        var info=new LinkedHashMap<String,String>();info.put("protocol","68");info.put("challenge",challenge);info.put("qport","31001");
        info.put("name","Native packet QA");info.put("model","sarge/default");info.put("headmodel","sarge/default");info.put("rate","25000");info.put("snaps","20");info.put("handicap","100");
        byte[] connect=ConnectPacketCodec.compress(ConnectionlessPacket.text("connect "+(char)34+dev.bluevista.craftq3.core.cvar.InfoString.encode(info,1024)+(char)34));
        host.receive(peer.localAddress(),connect,1);
        check(ConnectionlessMessage.parse(host.peekPacket().orElseThrow().payload()).line().equals("connectResponse"),"Connect rejected");host.packetSent();
        nativeClient.call("seed "+challenge+" 31001");
        int generation=0,lastSnapshot=0,snapshots=0,sent=0,dropped=0,firstSnapshots=0;
        double originX=0,originY=0,moved=0;
        for(int frame=0;frame<720;frame++) {
          long now=frame*16L;
          if(frame==240){game.restart(42);System.out.println("RESTART");}
          if(frame==440) {
            firstSnapshots=snapshots;var previous=game;game=game(fs,cvars,"q3dm17");host.replaceGame(game,now);previous.close();System.out.println("MAP q3dm17");
          }
          if(frame==160)nativeClient.command("cp 1 obsolete-response");
          nativeClient.call("step "+now+" "+game.time()+" 127 "+((frame/45)*16384));
          for(byte[] packet:nativeClient.packets)check(peer.send(udp.localAddress(),packet),"Native packet write blocked");nativeClient.packets.clear();
          for(int i=0;i<64;i++){var packet=udp.poll();if(packet.isEmpty())break;var p=packet.orElseThrow();host.receive(p.peer(),p.payload(),now);}
          if(frame%3==0)game.runFrame(game.time()+50);host.tick(now);
          for(int i=0;i<128;i++){
            var packet=host.peekPacket();if(packet.isEmpty())break;var p=packet.orElseThrow();
            if(frame>100&&++sent%11==0){host.packetSent();dropped++;continue;}
            check(udp.send(p.peer(),p.payload()),"Host write blocked");host.packetSent();
          }
          for(int i=0;i<128;i++){
            var packet=peer.poll();if(packet.isEmpty())break;
            nativeClient.call("receive "+HexFormat.of().formatHex(packet.orElseThrow().payload()));
            if(nativeClient.value(1)!=generation) {
              generation=nativeClient.value(1);System.out.println("GAMESTATE frame="+frame+" "+Arrays.toString(nativeClient.state));fs.read(new VirtualPath("vm/cgame.qvm"));fs.read(new VirtualPath("vm/ui.qvm"));
              nativeClient.command("cp "+nativeClient.value(2)+" "+fs.referencedPureChecksums());
            }
            if(nativeClient.value(4)!=0&&nativeClient.value(5)!=lastSnapshot) {
              lastSnapshot=nativeClient.value(5);snapshots++;
              double x=Double.parseDouble(nativeClient.state[8]),y=Double.parseDouble(nativeClient.state[9]);
              if(snapshots==1){originX=x;originY=y;}
              if(frame<440)moved=Math.max(moved,Math.hypot(x-originX,y-originY));
            }
          }
        }
        check(host.clients().size()==1&&host.clients().getFirst().active(),"Native peer is not active: "+host.clients());
        check(generation==2&&firstSnapshots>60&&snapshots-firstSnapshots>40,"Map/restart snapshot continuity failed: "+generation+" / "+firstSnapshots+" / "+snapshots);
        check(moved>50,"Native movement missing: "+moved);
        check(nativeClient.value(2)==12347,"Native configstring processing missed server ID: "+Arrays.toString(nativeClient.state));
        System.out.println("PASS native established-client UDP generations="+generation+" snapshots="+snapshots+" firstMap="+firstSnapshots+" movement="+moved+" dropped="+dropped+" restart/map/stalePure");
      } finally {game.close();}
    }
  }
  static Q3Server game(Pk3FileSystem fs,CvarSystem cvars,String map)throws Exception {
    var game=new Q3Server(fs,map,BspReader.read(fs.read(new VirtualPath("maps/"+map+".bsp"))),null,System.out::print,Clock.systemUTC(),null,cvars);
    game.initialize(1000,42);return game;
  }
  static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
}
