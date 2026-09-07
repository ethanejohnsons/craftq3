/** Authored observation driver for the production master parser and status queue. */
import dev.bluevista.craftq3.core.net.*;
import dev.bluevista.craftq3.client.net.BrowserStatus;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class BrowserDiscoveryJavaOracle {
 public static void main(String[]args)throws Exception{
  var status=new BrowserStatus((address,packet)->System.out.println("SEND 0 4 "+address.getPort()+" "+HexFormat.of().formatHex(ConnectionlessPacket.payload(packet))));
  int now=0,resend=750;
  try(var in=new BufferedReader(new InputStreamReader(System.in))){for(String line;(line=in.readLine())!=null;){String[]a=line.split(" ");
   switch(a[0]){
    case "raw"->{int scope=Integer.parseInt(a[3]);byte[]b=HexFormat.of().parseHex(a[4]);var result=MasterServerResponse.parse(b,scope);System.out.println("RAW "+result.endpoints().size());for(var p:result.endpoints())System.out.println("ENDPOINT "+(p.addressBytes().length==4?4:5)+" "+p.port()+" "+p.scopeId()+" "+HexFormat.of().formatHex(p.addressBytes()));}
    case "clear"->{status.resetAll();System.out.println("CLEAR");}
    case "tick"->{now=Integer.parseInt(a[1]);resend=Integer.parseInt(a[2]);System.out.println("TICK");}
    case "status"->{int cap=Integer.parseInt(a[1]);var address=endpoint(a[2]);var result=status.query(address,cap,now,resend);byte[]bytes=new byte[cap];if(result.isEmpty())Arrays.fill(bytes,(byte)0x55);else System.arraycopy(result.get().getBytes(StandardCharsets.ISO_8859_1),0,bytes,0,result.get().length());System.out.println("STATUS "+(result.isPresent()?1:0)+" "+HexFormat.of().formatHex(bytes));}
    case "cancel"->{status.reset(endpoint(a[1]));System.out.println("CANCEL 0");}
    case "response"->{status.receive(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),Integer.parseInt(a[1])),a[2].equals("-")?new byte[0]:HexFormat.of().parseHex(a[2]));System.out.println("RESPONSE");}
    default->throw new IllegalArgumentException(line);
   }
  }}
 }
 static InetSocketAddress endpoint(String hex)throws Exception{String raw=new String(HexFormat.of().parseHex(hex),StandardCharsets.ISO_8859_1);int colon=raw.lastIndexOf(':');return new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),Integer.parseInt(raw.substring(colon+1)));}
}
