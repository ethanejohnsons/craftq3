import dev.bluevista.craftq3.botlib.aas.*;
import dev.bluevista.craftq3.botlib.movement.*;
import dev.bluevista.craftq3.core.math.*;
import java.io.*;
import java.util.*;

class TargetBoxPredictionProbe {
  static class World implements AasMovementPredictor.World {
    float[] f;
    int traces, presences;
    long hash = 0xcbf29ce484222325L;

    void add(int i) {
      hash = (hash ^ Integer.toUnsignedLong(i)) * 1099511628211L;
    }

    void point(Vec3 v) {
      add(Float.floatToRawIntBits((float) v.x()));
      add(Float.floatToRawIntBits((float) v.y()));
      add(Float.floatToRawIntBits((float) v.z()));
    }

    World(float[] f) {
      this.f = f;
    }

    public AasPresenceTrace.Result trace(Vec3 s, Vec3 e, int p, int entity) {
      add(1);
      point(s);
      point(e);
      add(p);
      add(entity);
      traces++;
      float sx = (float) s.x(),
          sy = (float) s.y(),
          sz = (float) s.z(),
          ex = (float) e.x(),
          ey = (float) e.y(),
          ez = (float) e.z();
      float from = f[20] * sx + f[21] * sy + f[22] * sz - f[23],
          to = f[20] * ex + f[21] * ey + f[22] * ez - f[23];
      boolean solid = false;
      float fraction = 1;
      if (f[3] != 0 && to < 0) {
        if (from < 0) {
          solid = true;
          fraction = 0;
          ex = sx;
          ey = sy;
          ez = sz;
        } else {
          fraction = from / (from - to);
          ex = sx + fraction * (ex - sx);
          ey = sy + fraction * (ey - sy);
          ez = sz + fraction * (ez - sz);
        }
      }
      if (System.getenv("TRACE_MOVES") != null)
        System.err.printf(
            Locale.ROOT,
            "TRACE p%d %.9g %.9g %.9g -> %.9g %.9g %.9g = %.9g %d %.9g %.9g %.9g%n",
            p,
            sx,
            sy,
            sz,
            e.x(),
            e.y(),
            e.z(),
            fraction,
            solid ? 1 : 0,
            ex,
            ey,
            ez);
      return new AasPresenceTrace.Result(solid, fraction, new Vec3(ex, ey, ez), 0, 1, 0, 0, 1);
    }

    public Vec3 planeNormal(int p) {
      return new Vec3(f[20], f[21], f[22]);
    }

    public int area(Vec3 p) {
      add(4);
      point(p);
      return 1;
    }

    public int areaContents(int area) {
      return Integer.parseInt(System.getenv().getOrDefault("AREA_CONTENTS", "0"));
    }

    public int contents(Vec3 p) {
      int out = (int) f[19];
      if (System.getenv("FLUID_Z") != null)
        out = p.z() < Float.parseFloat(System.getenv("FLUID_Z")) ? out : 0;
      if (System.getenv("FLUID_X") != null)
        out = p.x() > Float.parseFloat(System.getenv("FLUID_X")) ? out : 0;
      add(3);
      point(p);
      add(out);
      return out;
    }

    public int presence(Vec3 p) {
      presences++;
      int result = p.x() < f[2] ? (int) f[0] : (int) f[1];
      add(2);
      point(p);
      add(result);
      return result;
    }
  }

  static void putVec(java.nio.ByteBuffer b, Vec3 v) {
    b.putFloat((float) v.x()).putFloat((float) v.y()).putFloat((float) v.z());
  }

  static Vec3 v(float[] f, int o) {
    return new Vec3(f[o], f[o + 1], f[o + 2]);
  }

  public static void main(String[] args) throws Exception {
    var in = new BufferedReader(new InputStreamReader(System.in));
    for (String l; (l = in.readLine()) != null; ) {
      String[] t = l.split(" ");
      float[] f = new float[t.length];
      for (int k = 0; k < f.length; k++) f[k] = Float.parseFloat(t[k]);
      var w = new World(f);
      var p = new AasMovementPredictor(w, AasMovementPredictor.Settings.from(System.getenv()));
      try {
        var op =
            p.predictHitBox(
                new AasMovementPredictor.Request(
                    3, v(f, 10), (int) f[4], f[5] != 0, v(f, 13), v(f, 16), (int) f[6], (int) f[7],
                    f[8], 0),
                v(f, 24),
                v(f, 27));
        if (op.isEmpty()) {
          System.out.println(
              "MOVE 0 0 0 0 0 0 0 0 0 0 0 0 trace0 0 count"
                  + w.traces
                  + " presence"
                  + w.presences
                  + " hash"
                  + Long.toUnsignedString(w.hash));
          System.out.println("RAW " + "7f".repeat(84));
          continue;
        }
        var r = op.get();
        var tr = r.trace().orElseThrow();
        System.out.printf(
            Locale.ROOT,
            "MOVE 1 %.9g %.9g %.9g %.9g %.9g %.9g %d %d %d %.9g %d trace%d %.9g count%d presence%d"
                + " hash%s%n",
            r.endPosition().x(),
            r.endPosition().y(),
            r.endPosition().z(),
            r.velocity().x(),
            r.velocity().y(),
            r.velocity().z(),
            r.presence(),
            r.stopEvent(),
            r.endContents(),
            r.time(),
            r.frames(),
            tr.startSolid() ? 1 : 0,
            tr.fraction(),
            w.traces,
            w.presences,
            Long.toUnsignedString(w.hash));
        var b = java.nio.ByteBuffer.allocate(84).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        putVec(b, r.endPosition());
        b.putInt(r.endArea());
        putVec(b, r.velocity());
        b.putInt(tr.startSolid() ? 1 : 0).putFloat(tr.fraction());
        putVec(b, tr.endPosition());
        b.putInt(tr.entity())
            .putInt(tr.lastArea())
            .putInt(tr.area())
            .putInt(tr.planeNumber())
            .putInt(r.presence())
            .putInt(r.stopEvent())
            .putInt(r.endContents())
            .putFloat(r.time())
            .putInt(r.frames());
        System.out.println("RAW " + HexFormat.of().formatHex(b.array()));
      } catch (Exception e) {
        System.out.println("EXCEPTION " + e);
      }
    }
  }
}
