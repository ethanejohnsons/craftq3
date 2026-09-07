import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.assets.aas.AasMap.*;
import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.botlib.aas.AasAreaVolume;
import dev.bluevista.craftq3.core.math.Vec3;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Original-area and authored-geometry differential; no extracted native data is persisted. */
class AuditAreaVolumes {
  private static int queries, differences;
  private static final Vec3 ZERO = new Vec3(0, 0, 0);

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException(
          "AuditAreaVolumes PK3 nativeOracle [authoredMeshes=10000]");
    int fixtures = args.length == 3 ? Integer.parseInt(args[2]) : 10000;
    if (fixtures < 0 || fixtures > 100000)
      throw new IllegalArgumentException("Invalid fixture count");
    int maps = 0;
    try (var oracle = new Oracle(args[1]);
        var zip = new ZipFile(args[0])) {
      for (var entry :
          zip.stream()
              .filter(e -> e.getName().startsWith("maps/") && e.getName().endsWith(".aas"))
              .sorted(Comparator.comparing(java.util.zip.ZipEntry::getName))
              .toList()) {
        AasMap map;
        try (var input = zip.getInputStream(entry)) {
          map = AasReader.read(input.readNBytes(AasReader.MAX_BYTES + 1));
        }
        oracle.mesh(map);
        var volume = new AasAreaVolume(map);
        int before = differences;
        for (int area = 0; area < map.areas().size(); area++)
          compare(entry.getName() + ":" + area, volume.volume(area), oracle.volume(area));
        maps++;
        System.out.println(
            entry.getName()
                + " areas="
                + map.areas().size()
                + " differences="
                + (differences - before));
      }
      Random random = new Random(680205);
      int before = differences;
      for (int index = 0; index < fixtures; index++) {
        AasMap map = authored(random, index);
        oracle.mesh(map);
        compare("authored:" + index, new AasAreaVolume(map).volume(1), oracle.volume(1));
      }
      System.out.println("Authored meshes=" + fixtures + " differences=" + (differences - before));
    }
    System.out.println(
        "Area volumes maps=" + maps + " queries=" + queries + " differences=" + differences);
    if (maps == 0 || differences != 0)
      throw new AssertionError("Native area-volume differential failed");
  }

  private static void compare(String label, float actual, int expected) {
    queries++;
    if (Float.floatToIntBits(actual) != expected) {
      if (differences++ < 20)
        System.out.println(
            "DIFF " + label + " java=" + actual + " native=" + Float.intBitsToFloat(expected));
    }
  }

  private static AasMap authored(Random random, int sample) {
    float[] origin = new float[3];
    float[][] axes = new float[3][3];
    for (int k = 0; k < 3; k++) {
      origin[k] = (random.nextFloat() - .5f) * 20000;
      for (int j = 0; j < 3; j++) axes[k][j] = (random.nextFloat() - .5f) * 1000;
    }
    if (sample % 50 == 0) Arrays.fill(axes[2], 0);
    var vertices = new ArrayList<Vec3>();
    for (int i = 0; i < 8; i++) {
      float[] p = origin.clone();
      for (int axis = 0; axis < 3; axis++)
        if ((i & (1 << axis)) != 0) for (int k = 0; k < 3; k++) p[k] += axes[axis][k];
      vertices.add(new Vec3(p[0], p[1], p[2]));
    }
    int[][] loops = {
      {0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}
    };
    var edges = new ArrayList<Edge>();
    edges.add(new Edge(0, 0));
    var faces = new ArrayList<Face>();
    faces.add(new Face(0, 0, 0, 0, 0, 0));
    var planes = new ArrayList<Plane>();
    int[] edgeIndices = new int[24], boundaries = new int[6];
    for (int i = 0; i < 6; i++) {
      for (int j = 0; j < 4; j++) {
        edgeIndices[i * 4 + j] = edges.size();
        edges.add(new Edge(loops[i][j], loops[i][(j + 1) % 4]));
      }
      if (random.nextBoolean())
        for (int j = 0; j < 4; j++) edgeIndices[i * 4 + j] = -(i * 4 + 4 - j);
      Vec3 a = vertices.get(loops[i][0]),
          b = vertices.get(loops[i][1]),
          c = vertices.get(loops[i][2]);
      float ux = (float) b.x() - (float) a.x(),
          uy = (float) b.y() - (float) a.y(),
          uz = (float) b.z() - (float) a.z();
      float vx = (float) c.x() - (float) a.x(),
          vy = (float) c.y() - (float) a.y(),
          vz = (float) c.z() - (float) a.z();
      float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
      float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
      if (length != 0) {
        nx /= length;
        ny /= length;
        nz /= length;
      }
      float distance = nx * (float) a.x() + ny * (float) a.y() + nz * (float) a.z();
      if (sample % 13 == 0) {
        nx *= 2;
        ny *= 2;
        nz *= 2;
      }
      planes.add(new Plane(new Vec3(nx, ny, nz), distance, 3));
      planes.add(new Plane(new Vec3(-nx, -ny, -nz), -distance, 3));
      boolean front = random.nextBoolean();
      faces.add(new Face(i * 2 + (front ? 1 : 0), 0, 4, i * 4, front ? 1 : 0, front ? 0 : 1));
      boundaries[i] = (random.nextBoolean() ? 1 : -1) * (i + 1);
    }
    for (int i = 5; i > 0; i--) {
      int j = random.nextInt(i + 1), v = boundaries[i];
      boundaries[i] = boundaries[j];
      boundaries[j] = v;
    }
    var areas =
        List.of(
            new Area(0, 0, 0, ZERO, ZERO, ZERO),
            new Area(1, 6, 0, ZERO, ZERO, new Vec3(9, -7, 100)));
    return new AasMap(
        4,
        0,
        List.of(),
        List.of(),
        vertices,
        planes,
        edges,
        new Indices(edgeIndices),
        faces,
        new Indices(boundaries),
        areas,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Indices(new int[0]),
        List.of());
  }

  private static void row(StringBuilder output, Object... values) {
    for (var value : values) output.append(value).append(' ');
    output.append('\n');
  }

  private static String meshText(AasMap map) {
    StringBuilder text = new StringBuilder("mesh ");
    row(
        text,
        map.vertices().size(),
        map.edges().size(),
        map.edgeIndices().size(),
        map.faces().size(),
        map.faceIndices().size(),
        map.areas().size(),
        map.planes().size());
    for (var v : map.vertices()) row(text, (float) v.x(), (float) v.y(), (float) v.z());
    for (var e : map.edges()) row(text, e.startVertex(), e.endVertex());
    for (int e : map.edgeIndices().toArray()) row(text, e);
    for (var f : map.faces())
      row(text, f.plane(), f.flags(), f.edgeCount(), f.firstEdge(), f.frontArea(), f.backArea());
    for (int f : map.faceIndices().toArray()) row(text, f);
    for (var a : map.areas())
      row(
          text,
          a.number(),
          a.faceCount(),
          a.firstFace(),
          (float) a.min().x(),
          (float) a.min().y(),
          (float) a.min().z(),
          (float) a.max().x(),
          (float) a.max().y(),
          (float) a.max().z(),
          (float) a.center().x(),
          (float) a.center().y(),
          (float) a.center().z());
    for (var p : map.planes())
      row(
          text,
          (float) p.normal().x(),
          (float) p.normal().y(),
          (float) p.normal().z(),
          p.distance(),
          p.type());
    return text.toString();
  }

  private static final class Oracle implements AutoCloseable {
    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;

    Oracle(String executable) throws IOException {
      process =
          new ProcessBuilder(executable).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      writer =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII));
      reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII));
    }

    void mesh(AasMap map) throws IOException {
      writer.write(meshText(map));
      writer.flush();
      if (!"READY".equals(reader.readLine())) throw new IOException("Native mesh fixture failed");
    }

    int volume(int area) throws IOException {
      writer.write("volume " + area + "\n");
      writer.flush();
      String line = reader.readLine();
      if (line == null || !line.startsWith("VOLUME " + area + " "))
        throw new IOException("Native volume query failed");
      return Integer.parseUnsignedInt(line.split(" ")[3], 16);
    }

    @Override
    public void close() throws Exception {
      try {
        writer.close();
        reader.close();
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0)
          throw new IOException("Native volume observer failed");
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
    }
  }
}
