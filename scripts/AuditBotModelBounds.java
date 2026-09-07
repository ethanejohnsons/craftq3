import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Black-box engine botlib import bounds, including any-angle sphere expansion and float order. */
class AuditBotModelBounds {
  public static void main(String[]args)throws Exception {
    var process=new ProcessBuilder(Path.of(".tools/server-message-oracle/bot-model-bounds-oracle").toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    var rng=new Random(0x25680225);int checks=0;
    try(var input=process.outputWriter(StandardCharsets.US_ASCII);var output=process.inputReader(StandardCharsets.US_ASCII)) {
      for(int trial=0;trial<10_000;trial++) {
        float[] mins=new float[3],maxs=new float[3],angles=new float[3];
        for(int i=0;i<3;i++){mins[i]=rng.nextFloat()*20000-10000;maxs[i]=mins[i]+rng.nextFloat()*10000;}
        if(trial%5!=0)angles[trial%3]=switch(trial%7){case 0->360;case 1->-360;case 2->Float.MIN_VALUE;case 3->-Float.MIN_VALUE;case 4->90;case 5->-0.0f;default->rng.nextFloat()*720-360;};
        for(float value:mins)input.write(Float.toString(value)+" ");for(float value:maxs)input.write(Float.toString(value)+" ");for(float value:angles)input.write(Float.toString(value)+" ");input.write("\n");input.flush();
        String[] parts=output.readLine().split(" ");float[] expected=new float[9];System.arraycopy(mins,0,expected,0,3);System.arraycopy(maxs,0,expected,3,3);
        if(angles[0]!=0||angles[1]!=0||angles[2]!=0) {
          float x=Math.max(Math.abs(mins[0]),Math.abs(maxs[0])),y=Math.max(Math.abs(mins[1]),Math.abs(maxs[1])),z=Math.max(Math.abs(mins[2]),Math.abs(maxs[2]));
          float radius=(float)Math.sqrt(x*x+y*y+z*z);Arrays.fill(expected,0,3,-radius);Arrays.fill(expected,3,6,radius);
        }
        for(int i=0;i<9;i++)if(Float.floatToRawIntBits(Float.parseFloat(parts[i]))!=Float.floatToRawIntBits(expected[i]))throw new AssertionError("Bounds query="+trial+" field="+i+" expected="+expected[i]+" actual="+parts[i]);
        checks++;
      }
    }finally{process.destroy();}
    System.out.printf("Engine bot model bounds PASS: %,d exact nine-float results over asymmetric bounds, zero/negative-zero, tiny and wrapped rotations%n",checks);
  }
}
