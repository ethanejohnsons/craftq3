package dev.bluevista.craftq3.mc.fabric.client.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.DynamicUniformStorage;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Per-view uniforms ({@code Q3View}): the view-projection matrix, the eye position used by {@code
 * tcGen environment}, and the shader time in seconds that drives every wave and tcMod.
 */
public record Q3ViewParams(Matrix4fc viewProjection, float eyeX, float eyeY, float eyeZ, float time)
    implements DynamicUniformStorage.DynamicUniform {
  public static final int UBO_SIZE = new Std140SizeCalculator().putMat4f().putVec4().get();

  public Q3ViewParams {
    viewProjection = new Matrix4f(viewProjection);
  }

  @Override
  public void write(ByteBuffer buffer) {
    Std140Builder.intoBuffer(buffer).putMat4f(viewProjection).putVec4(eyeX, eyeY, eyeZ, time);
  }
}
