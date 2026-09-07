import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Semantic checks for the original craftq3_shaderlab scene at shader time 1.0. */
class VerifyShaderCapture {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: java scripts/VerifyShaderCapture.java <capture.png>");
    BufferedImage image = ImageIO.read(Path.of(args[0]).toFile());
    if (image == null) throw new IllegalArgumentException("Not an image");
    // Horizontal-FOV projection: eight panels at x=(-270,-90,90,270), y=0,
    // z=(260,80), viewed from (0,-480,190), yaw90, FOV90.
    for (int row = 0; row < 2; row++) {
      for (int col = 0; col < 4; col++) {
        int[] counts = panel(image,col,row);
        if (counts[0] < counts[4] / 3) throw new AssertionError("Missing panel " + row + "," + col);
        if (row == 0 && col == 2 && counts[1] < counts[4] / 20) throw new AssertionError("Alpha cutout does not reveal blue backplate");
        if (row == 1 && col == 1 && counts[2] < counts[4] / 4) throw new AssertionError("animMap did not select green frame at 1.0 seconds");
        if (row == 1 && col == 3 && counts[3] < counts[4] / 10) throw new AssertionError("Mirror does not show the orange reflection-only card");
      }
    }
    checkSky(image, 100.0/1708, 100.0/960, 51, 6, 10);
    checkSky(image, 1600.0/1708, 100.0/960, 6, 13, 51);
    System.out.println("Shader capture passed: all eight panels, alpha holes, animation frame, mirror reflection, separate sky materials.");
  }
  static void checkSky(BufferedImage image,double x,double y,int r,int g,int b) {
    int pixel=image.getRGB((int)(x*image.getWidth()),(int)(y*image.getHeight()));
    if(Math.abs(((pixel>>>16)&255)-r)>2 || Math.abs(((pixel>>>8)&255)-g)>2 || Math.abs((pixel&255)-b)>2)
      throw new AssertionError("Sky coverage/color mismatch at " + x + "," + y + ": " + Integer.toHexString(pixel));
  }
  static int[] panel(BufferedImage image,int col,int row) {
    double focal=image.getWidth()/2.0;
    double cx=image.getWidth()/2.0+(-270+180*col)*focal/480;
    double cy=image.getHeight()/2.0-((row==0?260:80)-190)*focal/480;
    int radius=(int)(58*focal/480);
    int[] counts=new int[5];
    for(int y=(int)cy-radius;y<(int)cy+radius;y++) for(int x=(int)cx-radius;x<(int)cx+radius;x++) {
      if(x<0||y<0||x>=image.getWidth()||y>=image.getHeight())continue;
      int p=image.getRGB(x,y),r=(p>>>16)&255,g=(p>>>8)&255,b=p&255;
      if(r+g+b>65)counts[0]++;
      if(b>100&&b>r*1.5&&b>g*1.2)counts[1]++;
      if(g>70&&g>r*1.5&&g>b*1.25)counts[2]++;
      if(r>150&&r>g*1.5&&g>b*1.3)counts[3]++;
      counts[4]++;
    }
    return counts;
  }
}
