package ninja.trek.portal;

import java.util.function.Supplier;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import fi.dy.masa.malilib.util.position.Vec3d;
import fi.dy.masa.minihud.renderer.RenderObjectVbo;
import org.joml.Matrix4fStack;

public class PortalRenderObjectVbo extends RenderObjectVbo
{
    private Vec3d buildOrigin = Vec3d.ZERO;

    public PortalRenderObjectVbo(Supplier<String> name, RenderPipeline shader)
    {
        super(name, shader, 0);
    }

    public boolean isStartedPublic()
    {
        return super.isStarted();
    }

    public boolean isUploadedPublic()
    {
        return super.isUploaded();
    }

    public void uploadAtOrigin(MeshData meshData, boolean useTranslucentSorting, Vec3d buildOrigin)
    {
        super.upload(meshData, useTranslucentSorting);
        this.buildOrigin = buildOrigin;
    }

    public void lineWidthPublic(float width)
    {
        // Line width is now controlled by the RenderPipeline in 1.21.11+
    }

    public void drawPostPublic(boolean setLineWidth)
    {
        super.drawPost(null, false, setLineWidth);
    }

    public boolean shouldResortPublic()
    {
        return super.shouldResort();
    }

    public void resortTranslucentPublic(VertexSorting sorter)
    {
        super.resortTranslucent(sorter);
    }

    public VertexSorting createVertexSorterPublic(Vec3 pos)
    {
        return super.createVertexSorter(pos);
    }

    public VertexSorting createVertexSorterForCamera(Vec3d cameraPos)
    {
        return super.createVertexSorter(new Vec3(
                cameraPos.x - this.buildOrigin.x,
                cameraPos.y - this.buildOrigin.y,
                cameraPos.z - this.buildOrigin.z));
    }

    public void drawAtBuildOrigin(Vec3d rendererUpdateOrigin, Vec3d cameraPos, boolean shouldResort)
    {
        if (this.isStartedPublic() == false || this.isUploadedPublic() == false)
        {
            return;
        }

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();

        try
        {
            modelViewStack.translate(
                    (float) (this.buildOrigin.x - rendererUpdateOrigin.x),
                    (float) (this.buildOrigin.y - rendererUpdateOrigin.y),
                    (float) (this.buildOrigin.z - rendererUpdateOrigin.z));

            if (shouldResort && this.shouldResortPublic())
            {
                this.resortTranslucentPublic(this.createVertexSorterForCamera(cameraPos));
            }

            this.drawPostPublic(false);
        }
        finally
        {
            modelViewStack.popMatrix();
        }
    }

    public void closePublic()
    {
        super.close();
        this.buildOrigin = Vec3d.ZERO;
    }
}
