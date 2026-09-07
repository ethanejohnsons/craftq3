import dev.bluevista.craftq3.server.net.HostPacketTiming;
import java.io.*;
class AuditHostTiming {
 public static void main(String[] args)throws Exception {
  var input=new BufferedReader(new InputStreamReader(System.in));
  for(String line;(line=input.readLine())!=null;){String[] p=line.split(" ");
   if(p[0].equals("rate")) {
    int min=Integer.parseInt(p[2]),max=Integer.parseInt(p[3]);
    int delay=HostPacketTiming.interval(Integer.parseInt(p[4]),Integer.parseInt(p[1]),min,max,Float.parseFloat(p[7]),p[8].equals("6"));
    System.out.println("RATE "+Math.max(0,delay-(Integer.parseInt(p[6])-Integer.parseInt(p[5])))+" "+(min>0&&min<1000?1000:min)+" "+(max>0&&max<1000?1000:max));
   } else if(p[0].equals("ping")) {
    boolean active=p[1].equals("4")&&p[2].equals("1"),bot=p[3].equals("1");
    var timing=new HostPacketTiming();int count=Integer.parseInt(p[4]);
    for(int i=0;i<count;i++){timing.sent(i+1,100,true,Integer.parseInt(p[5+2*i]));int ack=Integer.parseInt(p[6+2*i]);if(ack>=0)timing.acknowledge(i+1,ack);}
    int ping=active?(bot?0:timing.ping()):999;System.out.println("PING "+ping+" "+(active?ping:0));
   } else if(p[0].equals("info")) {
    var info=dev.bluevista.craftq3.core.cvar.InfoString.parse(p[5],1024);
    System.out.println("INFO "+HostPacketTiming.clientRate(info.get("rate"))+" "+HostPacketTiming.snapshotInterval(info.get("snaps"),Integer.parseInt(p[4])));
   }
  }
 }
}
