import dev.bluevista.craftq3.assets.bsp.BspReader;
import dev.bluevista.craftq3.assets.fs.Pk3FileSystem;
import dev.bluevista.craftq3.client.net.RemoteConnection;
import dev.bluevista.craftq3.core.cvar.CvarSystem;
import dev.bluevista.craftq3.core.fs.VirtualPath;
import dev.bluevista.craftq3.platform.net.DatagramListener;
import dev.bluevista.craftq3.server.Q3Server;
import dev.bluevista.craftq3.server.UserCommand;
import dev.bluevista.craftq3.server.net.Protocol68Host;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;

/** Two actual UDP clients against hosted original qagame; private loopback only. */
class AuditHostedTiming {
  record Delayed(long at,Protocol68Host.Datagram packet) {}
  public static void main(String[] args)throws Exception {
    try(var fs=Pk3FileSystem.mount(Path.of(args[0]),"baseq3");
        var udp=new DatagramListener(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),0))) {
      var cvars=new CvarSystem();cvars.register("sv_cheats","0",CvarSystem.ROM|CvarSystem.SYSTEMINFO);
      cvars.register("bot_enable","0",0);cvars.register("sv_maxclients","4",0);
      try(var game=new Q3Server(fs,"q3dm1",BspReader.read(fs.read(new VirtualPath("maps/q3dm1.bsp"))),null,System.out::print,Clock.systemUTC(),null,cvars)) {
        game.initialize(1000,42);
        try(var host=new Protocol68Host(game,fs,12345,0,System.out::println);
            var first=RemoteConnection.open(udp.localAddress(),info("First"),101,31001,0);
            var second=RemoteConnection.open(udp.localAddress(),info("Second"),102,31002,0)) {
          var clients=List.of(first,second);int[] snapshots={0,0},sequences={0,0};boolean[] pure={false,false};
          double[][] initial=new double[2][];double[] moved={0,0};int sent=0,dropped=0;int lowSnapshots=0;var delayed=new ArrayDeque<Delayed>();var lastSend=new HashMap<Integer,Long>();var lastSize=new HashMap<Integer,Integer>();
          for(int frame=0;frame<700;frame++) {
            long now=frame*16L;
            while(!delayed.isEmpty()&&delayed.peekFirst().at()<=now){var packet=delayed.removeFirst().packet();check(udp.send(packet.peer(),packet.payload()),"Delayed send blocked");}
            if(frame==400){lowSnapshots=snapshots[0];first.session().orElseThrow().command("userinfo "+(char)34+dev.bluevista.craftq3.core.cvar.InfoString.encode(info("First fast"),1024)+(char)34);}
            for(int i=0;i<2;i++) {
              var client=clients.get(i);client.pump(now);
              var wire=client.session().orElse(null);
              if(wire!=null&&wire.gameState().isPresent()) {
                if(!pure[i]){
                  fs.read(new VirtualPath("vm/cgame.qvm"));fs.read(new VirtualPath("vm/ui.qvm"));
                  wire.command("cp 12345 "+fs.referencedPureChecksums());pure[i]=true;
                }
                client.userCommand(new UserCommand(game.time(),0,(frame/45)*16384,0,0,0,127,0,0));
                if(wire.snapshot().isPresent()) {
                  var snap=wire.snapshot().orElseThrow();
                  if(sequences[i]!=snap.sequence()){
                    sequences[i]=snap.sequence();snapshots[i]++;
                    var p=ByteBuffer.wrap(snap.player()).order(ByteOrder.LITTLE_ENDIAN);
                    double[] xy={p.getFloat(20),p.getFloat(24)};
                    if(initial[i]==null)initial[i]=xy;
                    moved[i]=Math.max(moved[i],Math.hypot(xy[0]-initial[i][0],xy[1]-initial[i][1]));
                  }
                }
              }
            }
            for(int i=0;i<64;i++){var packet=udp.poll();if(packet.isEmpty())break;host.receive(packet.orElseThrow().peer(),packet.orElseThrow().payload(),now);}
            if(frame%3==0)game.runFrame(game.time()+50);
            host.tick(now);
            for(int i=0;i<128;i++){
              var packet=host.peekPacket();if(packet.isEmpty())break;
              var value=packet.orElseThrow();
              // Loss starts after the handshake/initial snapshot and affects both real channels.
              if(!dev.bluevista.craftq3.core.net.ConnectionlessPacket.isConnectionless(value.payload())) {
                var owner=host.clients().stream().filter(c->c.address().equals(value.peer())).findFirst().orElseThrow();int port=value.peer().getPort();
                if(lastSend.containsKey(port)) {
                  long interval=dev.bluevista.craftq3.server.net.HostPacketTiming.interval(lastSize.get(port),owner.rate(),0,0,1,false);
                  check(now-lastSend.get(port)>=interval,"Peer exceeded its rate: "+owner+" interval "+interval+" elapsed "+(now-lastSend.get(port)));
                }
                lastSend.put(port,now);lastSize.put(port,value.payload().length);
              }
              if(frame>120&&++sent%11==0){host.packetSent();dropped++;continue;}
              delayed.addLast(new Delayed(now+96,value));host.packetSent();
            }
          }
          check(host.clients().size()==2&&host.clients().stream().allMatch(Protocol68Host.Client::active),"Hosted clients did not become active: "+host.clients());
          check(lowSnapshots>5&&lowSnapshots<50&&snapshots[0]-lowSnapshots>50&&snapshots[1]>100,"Snapshots missing: "+Arrays.toString(snapshots));
          check(moved[0]>50&&moved[1]>50,"Both players must move: "+Arrays.toString(moved));
          for(var owner:host.clients()) {
            check(owner.ping()>=96&&owner.ping()<500,"Ping did not measure delayed packets: "+owner);
            int vmPing=ByteBuffer.wrap(game.playerState(owner.number())).order(ByteOrder.LITTLE_ENDIAN).getInt(452);
            check(vmPing>=96&&vmPing<500,"Original qagame did not receive ping: "+vmPing);
          }
          host.receive(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),65000),dev.bluevista.craftq3.core.net.ConnectionlessPacket.text("getstatus timing"),11200);
          var status=dev.bluevista.craftq3.core.net.ConnectionlessMessage.parse(host.peekPacket().orElseThrow().payload());host.packetSent();
          check(status.line().equals("statusResponse")&&new String(status.body(),java.nio.charset.StandardCharsets.ISO_8859_1).contains(" "+host.clients().getFirst().ping()+" "),"Status did not publish measured ping");
          System.out.println("TIMING lowSnapshots="+lowSnapshots+" final="+Arrays.toString(snapshots)+" clients="+host.clients());
          System.out.println("CLIENT STATES "+first.state()+" / "+second.state());
          check(first.disconnect(),"Client was not connected at disconnect");
          for(int step=0;step<32;step++) {
            long now=11200+step*16L;first.pump(now);
            for(int i=0;i<64;i++){var packet=udp.poll();if(packet.isEmpty())break;host.receive(packet.orElseThrow().peer(),packet.orElseThrow().payload(),now);}
            host.tick(now);
            for(int i=0;i<128;i++){var packet=host.peekPacket();if(packet.isEmpty())break;var value=packet.orElseThrow();if(!udp.send(value.peer(),value.payload()))break;host.packetSent();}
          }
          check(host.clients().stream().filter(Protocol68Host.Client::active).count()==1,"Disconnect did not release original player");
          System.out.println("\nPASS hosted timing UDP "+game.abi()+" pure clients2 snapshots"+Arrays.toString(snapshots)+" moved"+Arrays.toString(moved)+" dropped"+dropped+" disconnect");
        }
      }
    }
  }
  static Map<String,String> info(String name){return Map.of("name",name,"model","sarge/default","rate",name.equals("First")?"1000":"25000","snaps","20");}
  static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
}
