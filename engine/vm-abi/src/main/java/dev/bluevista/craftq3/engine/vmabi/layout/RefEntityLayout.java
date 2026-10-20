package dev.bluevista.craftq3.engine.vmabi.layout;

import dev.bluevista.craftq3.engine.common.math.Vec3f;
import dev.bluevista.craftq3.engine.vm.QvmMemory;

/** {@code refEntity_t}, 140 bytes, identical in both profiles. */
public final class RefEntityLayout {
  public static final int RE_TYPE = 0;
  public static final int RENDERFX = 4;
  public static final int H_MODEL = 8;
  public static final int LIGHTING_ORIGIN = 12;
  public static final int SHADOW_PLANE = 24;
  public static final int AXIS = 28;
  public static final int NON_NORMALIZED_AXES = 64;
  public static final int ORIGIN = 68;
  public static final int FRAME = 80;
  public static final int OLDORIGIN = 84;
  public static final int OLDFRAME = 96;
  public static final int BACKLERP = 100;
  public static final int SKIN_NUM = 104;
  public static final int CUSTOM_SKIN = 108;
  public static final int CUSTOM_SHADER = 112;
  public static final int SHADER_RGBA = 116;
  public static final int SHADER_TEX_COORD = 120;
  public static final int SHADER_TIME = 128;
  public static final int RADIUS = 132;
  public static final int ROTATION = 136;
  public static final int BYTES = 140;

  private RefEntityLayout() {}

  /** A decoded {@code refEntity_t}; {@code shaderRgba} is packed as {@code 0xAABBGGRR}. */
  public record RefEntity(
      int reType,
      int renderfx,
      int hModel,
      Vec3f lightingOrigin,
      float shadowPlane,
      Vec3f[] axis,
      boolean nonNormalizedAxes,
      Vec3f origin,
      int frame,
      Vec3f oldOrigin,
      int oldFrame,
      float backlerp,
      int skinNum,
      int customSkin,
      int customShader,
      int shaderRgba,
      float shaderS,
      float shaderT,
      float shaderTime,
      float radius,
      float rotation) {}

  public static RefEntity read(QvmMemory memory, int address) {
    memory.resolve(address, BYTES);
    return new RefEntity(
        memory.readInt(address + RE_TYPE),
        memory.readInt(address + RENDERFX),
        memory.readInt(address + H_MODEL),
        VmStructs.readVec3(memory, address + LIGHTING_ORIGIN),
        memory.readFloat(address + SHADOW_PLANE),
        VmStructs.readAxis(memory, address + AXIS),
        VmStructs.readBoolean(memory, address + NON_NORMALIZED_AXES),
        VmStructs.readVec3(memory, address + ORIGIN),
        memory.readInt(address + FRAME),
        VmStructs.readVec3(memory, address + OLDORIGIN),
        memory.readInt(address + OLDFRAME),
        memory.readFloat(address + BACKLERP),
        memory.readInt(address + SKIN_NUM),
        memory.readInt(address + CUSTOM_SKIN),
        memory.readInt(address + CUSTOM_SHADER),
        memory.readInt(address + SHADER_RGBA),
        memory.readFloat(address + SHADER_TEX_COORD),
        memory.readFloat(address + SHADER_TEX_COORD + 4),
        memory.readFloat(address + SHADER_TIME),
        memory.readFloat(address + RADIUS),
        memory.readFloat(address + ROTATION));
  }

  public static void write(QvmMemory memory, int address, RefEntity entity) {
    memory.fill(address, BYTES, 0);
    memory.writeInt(address + RE_TYPE, entity.reType());
    memory.writeInt(address + RENDERFX, entity.renderfx());
    memory.writeInt(address + H_MODEL, entity.hModel());
    VmStructs.writeVec3(memory, address + LIGHTING_ORIGIN, entity.lightingOrigin());
    memory.writeFloat(address + SHADOW_PLANE, entity.shadowPlane());
    VmStructs.writeAxis(memory, address + AXIS, entity.axis());
    VmStructs.writeBoolean(memory, address + NON_NORMALIZED_AXES, entity.nonNormalizedAxes());
    VmStructs.writeVec3(memory, address + ORIGIN, entity.origin());
    memory.writeInt(address + FRAME, entity.frame());
    VmStructs.writeVec3(memory, address + OLDORIGIN, entity.oldOrigin());
    memory.writeInt(address + OLDFRAME, entity.oldFrame());
    memory.writeFloat(address + BACKLERP, entity.backlerp());
    memory.writeInt(address + SKIN_NUM, entity.skinNum());
    memory.writeInt(address + CUSTOM_SKIN, entity.customSkin());
    memory.writeInt(address + CUSTOM_SHADER, entity.customShader());
    memory.writeInt(address + SHADER_RGBA, entity.shaderRgba());
    memory.writeFloat(address + SHADER_TEX_COORD, entity.shaderS());
    memory.writeFloat(address + SHADER_TEX_COORD + 4, entity.shaderT());
    memory.writeFloat(address + SHADER_TIME, entity.shaderTime());
    memory.writeFloat(address + RADIUS, entity.radius());
    memory.writeFloat(address + ROTATION, entity.rotation());
  }
}
