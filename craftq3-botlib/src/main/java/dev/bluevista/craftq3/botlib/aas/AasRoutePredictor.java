package dev.bluevista.craftq3.botlib.aas;

import dev.bluevista.craftq3.assets.aas.AasMap;
import dev.bluevista.craftq3.core.math.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Bounded route inspection using the observed AAS cache-selected route contract. */
public final class AasRoutePredictor {
  public static final int NO_ROUTE = 1, USE_TRAVEL_TYPE = 2, ENTER_CONTENTS = 4, ENTER_AREA = 8;
  public static final int DEFAULT_MAX_STEPS = 16_384;

  public record Request(
      int startArea,
      Vec3 origin,
      int goalArea,
      TravelPolicy policy,
      int maxAreas,
      int maxTime,
      int stopEvents,
      int stopContents,
      int stopTravelFlags,
      int stopArea) {
    public Request {
      Objects.requireNonNull(origin);
      Objects.requireNonNull(policy);
      if (!Float.isFinite((float) origin.x())
          || !Float.isFinite((float) origin.y())
          || !Float.isFinite((float) origin.z()))
        throw new IllegalArgumentException("Route prediction origin must fit finite floats");
      origin = new Vec3((float) origin.x(), (float) origin.y(), (float) origin.z());
    }
  }

  /** The native numareas slot is unassigned: writeTo deliberately preserves bytes 28..31. */
  public record Prediction(
      Vec3 endPosition, int endArea, int stopEvent, int endContents, int endTravelFlags, int time) {
    public static final int BYTE_SIZE = 36;

    public Prediction {
      Objects.requireNonNull(endPosition);
    }

    public void writeTo(ByteBuffer target, int offset) {
      Objects.checkFromIndexSize(offset, BYTE_SIZE, target.limit());
      var out = target.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      out.putFloat(offset, (float) endPosition.x());
      out.putFloat(offset + 4, (float) endPosition.y());
      out.putFloat(offset + 8, (float) endPosition.z());
      out.putInt(offset + 12, endArea);
      out.putInt(offset + 16, stopEvent);
      out.putInt(offset + 20, endContents);
      out.putInt(offset + 24, endTravelFlags);
      out.putInt(offset + 32, time);
    }
  }

  public record Result(boolean success, Prediction prediction) {
    public Result {
      Objects.requireNonNull(prediction);
    }
  }

  private final AasMap map;
  private final AasRouteTimes routes;
  private final int maxSteps;
  private final LinkedHashMap<Integer, List<Integer>> crossedAreas =
      new LinkedHashMap<>(16, .75f, true);

  public AasRoutePredictor(AasNavigation navigation, AasRouteTimes routes) {
    this(navigation, routes, DEFAULT_MAX_STEPS);
  }

  public AasRoutePredictor(AasNavigation navigation, AasRouteTimes routes, int maxSteps) {
    map = Objects.requireNonNull(navigation).map();
    this.routes = Objects.requireNonNull(routes);
    if (routes.map() != map)
      throw new IllegalArgumentException("Routing and navigation maps differ");
    if (maxSteps < 1 || maxSteps > 1_000_000)
      throw new IllegalArgumentException("Invalid prediction work budget");
    this.maxSteps = maxSteps;
  }

  public synchronized Result predict(Request request) {
    Objects.requireNonNull(request);
    int area = request.startArea(),
        endArea = request.goalArea(),
        contents = 0,
        travel = 0,
        time = 0;
    Vec3 end = request.origin();
    int steps = 0;
    int requestedSteps =
        request.maxAreas() == 0
            ? map.areas().size()
            : Math.min(request.maxAreas(), map.areas().size());
    while (area != request.goalArea() && steps < requestedSteps) {
      if (steps++ == maxSteps)
        throw new IllegalStateException("AAS route prediction work budget exceeded");
      var route = routes.route(area, end, request.goalArea(), request.policy());
      int reachability = route.reachability();
      // Native raw portal routing can succeed with time zero and a stored first link.
      // Keep this prediction-only behavior separate from the travel-time provider's
      // unreachable result. Invalid/no-outgoing/cluster-zero goals never take it.
      if (reachability == 0
          && area > 0
          && area < map.areas().size()
          && request.goalArea() > 0
          && request.goalArea() < map.areas().size()
          && map.areaSettings().get(area).cluster() < 0
          && map.areaSettings().get(area).reachabilityCount() > 0
          && map.areaSettings().get(request.goalArea()).cluster() != 0
          && map.areaSettings().get(request.goalArea()).reachabilityCount() > 0)
        reachability = map.areaSettings().get(area).firstReachability();
      if (reachability == 0) return result(false, end, endArea, NO_ROUTE, contents, travel, time);
      var reach = map.reachabilities().get(reachability);
      int nextTravel = TravelFlags.forReachability(reach);
      if ((request.stopEvents() & USE_TRAVEL_TYPE) != 0
          && (nextTravel & request.stopTravelFlags()) != 0)
        return result(
            true,
            reach.start(),
            area,
            USE_TRAVEL_TYPE,
            map.areaSettings().get(area).contents(),
            nextTravel,
            time);
      int nextContents = map.areaSettings().get(reach.area()).contents();
      // Route selection advances with end; charged distance deliberately retains the
      // original area metric and origin, including crouch/liquid scaling.
      int nextTime =
          Math.addExact(
              time,
              Math.addExact(
                  routes.areaTravelTime(request.startArea(), request.origin(), reach.start()),
                  reach.travelTime()));
      int areaTravel = contentsTravelFlags(reach.area());
      if ((request.stopEvents() & USE_TRAVEL_TYPE) != 0
          && (areaTravel & request.stopTravelFlags()) != 0)
        return result(
            true, reach.end(), reach.area(), USE_TRAVEL_TYPE, nextContents, areaTravel, nextTime);
      if ((request.stopEvents() & (ENTER_CONTENTS | ENTER_AREA)) != 0) {
        var passed = crossedAreas(reachability);
        for (int i = 0; i <= passed.size(); i++) {
          int inspected = i == passed.size() ? reach.area() : passed.get(i);
          int inspectedContents = map.areaSettings().get(inspected).contents();
          if ((request.stopEvents() & ENTER_CONTENTS) != 0
              && (inspectedContents & request.stopContents()) != 0)
            return result(
                true, reach.end(), inspected, ENTER_CONTENTS, inspectedContents, travel, nextTime);
          if ((request.stopEvents() & ENTER_AREA) != 0 && inspected == request.stopArea())
            return result(
                true, reach.start(), inspected, ENTER_AREA, inspectedContents, travel, time);
        }
      }
      area = reach.area();
      endArea = area;
      end = reach.end();
      contents = nextContents;
      travel = nextTravel;
      time = nextTime;
      if (request.maxTime() != 0 && time > request.maxTime()) break;
    }
    return result(area == request.goalArea(), end, endArea, 0, contents, travel, time);
  }

  private List<Integer> crossedAreas(int reachIndex) {
    var reach = map.reachabilities().get(reachIndex);
    int type = reach.baseTravelType();
    if (type != 4 && type != 7 && type != 9) return List.of();
    var cached = crossedAreas.get(reachIndex);
    if (cached != null) return cached;
    Vec3 start =
        type != 7 ? reach.start() : new Vec3(reach.end().x(), reach.end().y(), reach.start().z());
    Vec3 end =
        type != 7 ? new Vec3(reach.start().x(), reach.start().y(), reach.end().z()) : reach.end();
    var entries =
        AasAreaTrace.trace(map, start, end, 32, 1_000_000).stream()
            .map(AasAreaTrace.Entry::area)
            .toList();
    crossedAreas.put(reachIndex, entries);
    if (crossedAreas.size() > 256) crossedAreas.remove(crossedAreas.keySet().iterator().next());
    return entries;
  }

  private int contentsTravelFlags(int area) {
    var settings = map.areaSettings().get(area);
    int contents = settings.contents(), flags = 0;
    if ((contents & 1) != 0) flags |= TravelFlags.WATER;
    if ((contents & 2) != 0) flags |= TravelFlags.LAVA;
    if ((contents & 4) != 0) flags |= TravelFlags.SLIME;
    if ((contents & 7) == 0) flags |= TravelFlags.AIR;
    if ((contents & 256) != 0) flags |= TravelFlags.DO_NOT_ENTER;
    if ((contents & 2048) != 0) flags |= TravelFlags.NOT_TEAM_1;
    if ((contents & 4096) != 0) flags |= TravelFlags.NOT_TEAM_2;
    if ((settings.flags() & 16) != 0) flags |= TravelFlags.BRIDGE;
    return flags;
  }

  private static Result result(
      boolean success, Vec3 end, int area, int event, int contents, int travel, int time) {
    return new Result(success, new Prediction(end, area, event, contents, travel, time));
  }
}
