package ninja.trek.portal;

import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexSorting;
import fi.dy.masa.minihud.renderer.RenderObjectVbo;

public class PortalRenderObjectVbo extends RenderObjectVbo
{
    public PortalRenderObjectVbo(Supplier<String> name, RenderPipeline shader)
    {
        super(name, shader);
    }

    public boolean isStartedPublic()
    {
        return super.isStarted();
    }

    public boolean isUploadedPublic()
    {
        return super.isUploaded();
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

    public void closePublic()
    {
        super.close();
    }
}
