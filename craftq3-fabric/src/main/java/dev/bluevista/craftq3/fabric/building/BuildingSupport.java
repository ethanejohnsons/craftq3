package dev.bluevista.craftq3.fabric.building;

import dev.bluevista.craftq3.assets.bsp.BspMap;
import dev.bluevista.craftq3.collision.BspTraceWorld;
import dev.bluevista.craftq3.core.math.Vec3;
import dev.bluevista.craftq3.platform.CoordinateTransform;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/** Native FULL/CENTER/RIGID support requirements projected onto real BSP/host faces. */
public final class BuildingSupport {
  private static final List<AABB> FULL = Shapes.block().toAabbs();
  // Minecraft 26.2 SupportType's required volumes. Projection preserves its side-face semantics.
  private static final List<AABB> CENTER =
      Shapes.box(7.0 / 16, 0, 7.0 / 16, 9.0 / 16, 10.0 / 16, 9.0 / 16).toAabbs();
  private static final List<AABB> RIGID =
      Shapes.join(
              Shapes.block(),
              Shapes.box(2.0 / 16, 0, 2.0 / 16, 14.0 / 16, 1, 14.0 / 16),
              BooleanOp.ONLY_FIRST)
          .toAabbs();
  private final BspTraceWorld bsp;
  private final CoordinateTransform transform;

  public BuildingSupport(BspTraceWorld bsp, CoordinateTransform transform) {
    this.bsp = bsp;
    this.transform = transform;
  }

  public boolean supports(
      BlockState state, BlockGetter level, BlockPos pos, Direction face, SupportType type) {
    var host =
        state.getBlockSupportShape(level, pos).getFaceShape(face).toAabbs().stream()
            .map(box -> bounds(box.move(pos)))
            .toList();
    var n = new Vec3(face.getStepX(), -face.getStepZ(), face.getStepY());
    for (var required :
        switch (type) {
          case FULL -> FULL;
          case CENTER -> CENTER;
          case RIGID -> RIGID;
        }) {
      double[] lo = {required.minX, required.minY, required.minZ},
          hi = {required.maxX, required.maxY, required.maxZ};
      int axis =
          switch (face.getAxis()) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
          };
      lo[axis] = hi[axis] = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
      var rectangle = bounds(new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]).move(pos));
      if (!bsp.supportsFace(rectangle.min(), rectangle.max(), n, host)) return false;
    }
    return true;
  }

  private BspMap.Bounds bounds(AABB box) {
    var a = transform.toQuake(new Vec3(box.minX, box.minY, box.minZ));
    var b = transform.toQuake(new Vec3(box.maxX, box.maxY, box.maxZ));
    return new BspMap.Bounds(
        new Vec3(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z())),
        new Vec3(Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z())));
  }
}
