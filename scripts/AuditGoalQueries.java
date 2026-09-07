import dev.bluevista.craftq3.botlib.goal.*;
import dev.bluevista.craftq3.collision.*;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Black-box boundary/random corpus for independently authored goal contact/visibility queries. */
class AuditGoalQueries {
  public static void main(String[] args)throws Exception {
    Path oracle=Path.of(args.length==0?".tools/item-oracle/goal-query-oracle":args[0]);
    var process=new ProcessBuilder(oracle.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    int checks=0;var random=new Random(0x601701);
    try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
      for(int i=0;i<12000;i++) {
        var center=vector(random,8192);var lo=vector(random,30);var hi=vector(random,30);
        var mins=new Vec3(-Math.abs(lo.x()),-Math.abs(lo.y()),-Math.abs(lo.z()));
        var maxs=new Vec3(Math.abs(hi.x()),Math.abs(hi.y()),Math.abs(hi.z()));
        var goal=new Goal(center,7,mins,maxs,40,3,random.nextInt(8),2);
        Vec3 origin;
        if(i%4==0)origin=new Vec3(center.x()+random.nextFloat()*200-100,center.y()+random.nextFloat()*200-100,center.z()+random.nextFloat()*200-100);
        else {
          int axis=random.nextInt(3);boolean upper=random.nextBoolean();
          float boundary=(float)component(center,axis)+(float)component(upper?maxs:mins,axis)-(upper?(axis==2?-24:-15):(axis==2?32:15));
          if(i%4==2)boundary=Math.nextUp(boundary);if(i%4==3)boundary=Math.nextDown(boundary);
          origin=new Vec3(axis==0?boundary:center.x(),axis==1?boundary:center.y(),axis==2?boundary:center.z());
        }
        // The ABI is float; quantize before querying either implementation.
        origin=new Vec3((float)origin.x(),(float)origin.y(),(float)origin.z());
        String command="touch "+text(origin)+text(center)+text(mins)+text(maxs)+goal.flags();
        input.write(command+"\n");input.flush();
        String expected="TOUCH "+(GoalQueries.touching(origin,goal)?1:0),actual=output.readLine();
        if(!expected.equals(actual))throw new AssertionError("Contact case "+i+" expected "+expected+" actual "+actual+" command "+command);
        checks++;
      }
      for(int i=0;i<6000;i++) {
        var eye=vector(random,8192);var angles=vector(random,720);
        var center=vector(random,8192);var mins=new Vec3(-10,-20,-30);var maxs=new Vec3(30,10,40);
        var goal=new Goal(center,7,mins,maxs,random.nextInt(4)==0?0:40,3,random.nextInt(8),2);
        float fraction=random.nextBoolean()?1:random.nextFloat(),updated=4+random.nextFloat(),update=random.nextFloat();
        if(i%5==0)updated=4.5f;if(i%5==1)updated=Math.nextDown(4.5f);
        final float f=fraction;
        TraceWorld world=new TraceWorld() {
          public TraceResult trace(TraceRequest request){return new TraceResult(f,request.end(),false,false,Optional.empty());}
          public int pointContents(Vec3 p,int mask,int ignored){return 0;}
        };
        final float last=updated;
        String command="vis 3 "+text(eye)+text(angles)+text(center)+text(mins)+text(maxs)+goal.flags()+" "+goal.entity()+" "+fraction+" "+random.nextInt(64)+" "+random.nextInt(2)+" "+random.nextInt(2)+" "+updated+" "+update;
        input.write(command+"\n");input.flush();
        String expected="VIS "+(GoalQueries.visibleButMissing(3,eye,angles,goal,5,world,n->last)?1:0),actual=output.readLine();
        if(!expected.equals(actual))throw new AssertionError("Visibility case "+i+" expected "+expected+" actual "+actual+" command "+command);
        checks++;
      }
    }
    if(process.waitFor()!=0)throw new AssertionError("Native goal query observer failed");
    System.out.printf("Goal contact/visibility PASS: %,d boundary/random checks; zero native mismatches%n",checks);
  }
  static Vec3 vector(Random r,float scale){return new Vec3((r.nextFloat()-.5f)*scale,(r.nextFloat()-.5f)*scale,(r.nextFloat()-.5f)*scale);}
  static double component(Vec3 v,int axis){return axis==0?v.x():axis==1?v.y():v.z();}
  static String text(Vec3 v){return (float)v.x()+" "+(float)v.y()+" "+(float)v.z()+" ";}
}
