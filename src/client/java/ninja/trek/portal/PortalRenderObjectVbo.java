package ninja.trek.portal;

import java.util.function.Supplier;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.util.math.Vec3d;
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
        super.lineWidth(width);
    }

    public void drawPostPublic(boolean setLineWidth)
    {
        super.drawPost(null, false, setLineWidth);
    }

    public boolean shouldResortPublic()
    {
        return super.shouldResort();
    }

    public void resortTranslucentPublic(VertexSorter sorter)
    {
        super.resortTranslucent(sorter);
    }

    public VertexSorter createVertexSorterPublic(Vec3d pos)
    {
        return super.createVertexSorter(pos);
    }

    public void closePublic()
    {
        super.close();
    }
}
