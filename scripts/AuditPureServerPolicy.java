import dev.bluevista.craftq3.core.command.CommandParser;
import dev.bluevista.craftq3.server.net.PureClientPolicy;
import java.io.*;
import java.util.*;

/** Same authored command corpus as the unchanged native pure validator. */
class AuditPureServerPolicy {
  public static void main(String[] args)throws Exception {
    var input=new BufferedReader(new InputStreamReader(System.in));
    for(String line;(line=input.readLine())!=null;) {
      String[] fields=line.split("\\|",4);
      System.out.println(PureClientPolicy.verify(CommandParser.tokenize(fields[3]),
          fields[0].equals("1"),Integer.parseInt(fields[1]),Integer.parseInt(fields[2]),111,222,Set.of(111,222,333,444)));
    }
  }
}
