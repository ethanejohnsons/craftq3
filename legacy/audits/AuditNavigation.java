import dev.bluevista.craftq3.assets.aas.AasReader;
import dev.bluevista.craftq3.botlib.aas.AasNavigation;
import dev.bluevista.craftq3.botlib.aas.TravelFlags;
import dev.bluevista.craftq3.botlib.aas.TravelPolicy;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipFile;

/** Read-only consistency audit, not an oracle of original botlib AI or route timing. */
class AuditNavigation {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: AuditNavigation.java /path/to/pak0.pk3");
    int maps = 0, failures = 0;
    long centers = 0, traces = 0, routes = 0, outsideCenters = 0;
    var policy = TravelPolicy.ofFlags(TravelFlags.ALL);
    try (ZipFile archive = new ZipFile(Path.of(args[0]).toFile())) {
      var entries = archive.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".aas")) continue;
        try (var input = archive.getInputStream(entry)) {
          var map = AasReader.read(input.readNBytes(AasReader.MAX_BYTES + 1));
          var nav = new AasNavigation(map);
          int checkedRoutes = 0, checkedTraces = 0;
          for (int area = 1; area < map.areas().size(); area++) {
            var center = map.areas().get(area).center();
            int classified = nav.pointArea(center);
            if (classified != area) {
              // Stored center metadata is not guaranteed to lie inside every stored face half-space.
              var bounds = map.areas().get(area);
              double violation = 0;
              for (int faceIndex = bounds.firstFace(); faceIndex < bounds.firstFace() + bounds.faceCount(); faceIndex++) {
                int oriented = map.faceIndices().get(faceIndex);
                var plane = map.planes().get(map.faces().get(Math.abs(oriented)).plane());
                var n = plane.normal();
                double distance = n.x() * center.x() + n.y() * center.y() + n.z() * center.z() - plane.distance();
                violation = Math.max(violation, oriented > 0 ? -distance : distance);
              }
              require(violation > 0, "center classification contradicts its stored face half-spaces: " + area);
              outsideCenters++;
              System.out.printf("NOTE %s area=%d stored center outside own faces by %.9f units, classified=%d%n", entry.getName(), area, violation, classified);
            }
            var box = nav.boxAreas(center, center, 64);
            require(box.complete() && (classified == 0 || box.areas().contains(classified)), "center point/box disagreement " + area);
            centers++;
            if (checkedTraces < 128 && area % Math.max(1, map.areas().size() / 128) == 0) {
              var end = map.areas().get(1 + (area * 7919) % (map.areas().size() - 1)).center();
              var trace = nav.traceAreas(center, end, 4096);
              require(trace.complete(), "trace budget");
              double previous = 0;
              for (var span : trace.spans()) {
                require(span.enterFraction() >= previous && span.exitFraction() >= span.enterFraction(), "trace ordering");
                if (span.exitFraction() - span.enterFraction() > 1e-8) {
                  var midpoint = span.entry().add(span.exit()).scale(0.5);
                  require(nav.pointArea(midpoint) == span.area(), "trace midpoint");
                }
                previous = span.exitFraction();
              }
              traces++; checkedTraces++;
            }
            if (checkedRoutes < 128 && area % Math.max(1, map.areas().size() / 256) == 0) {
              for (var direct : nav.reachabilities(area, policy)) {
                var result = nav.route(area, direct.reachability().area(), policy, AasNavigation.SearchBudget.defaults());
                require(result.found() && result.travelTime() <= direct.reachability().travelTime(), "direct link route");
                int source = area;
                long cost = 0;
                for (var step : result.links()) {
                  require(step.sourceArea() == source, "route continuity");
                  source = step.reachability().area(); cost += step.reachability().travelTime();
                }
                require(source == direct.reachability().area() && cost == result.travelTime(), "route endpoint/cost");
                routes++; checkedRoutes++;
                break;
              }
            }
          }
          maps++;
          System.out.printf("PASS %s centers=%d traces=%d routes=%d%n", entry.getName(), map.areas().size() - 1, checkedTraces, checkedRoutes);
        } catch (Exception failure) {
          failures++;
          System.out.printf("FAIL %s: %s%n", entry.getName(), failure.getMessage());
        }
      }
    }
    System.out.printf("Navigation maps=%d failures=%d centers=%d outsideStoredCenters=%d traces=%d routes=%d%n", maps, failures, centers, outsideCenters, traces, routes);
    if (failures != 0) System.exit(1);
  }
  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
