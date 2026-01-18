package ninja.trek.portal;

import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.interfaces.IRangeChangeListener;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.minihud.renderer.OverlayRendererBase;
import fi.dy.masa.minihud.renderer.RenderUtils;

public class PortalGhostRenderer extends OverlayRendererBase implements IRangeChangeListener
{
    public static final PortalGhostRenderer INSTANCE = new PortalGhostRenderer();
    private static final int FRAME_COLOR = 0xFFFFFF;
    private static final float FRAME_ALPHA = 0.4f;

    private final LayerRange layerRange = new LayerRange(this);
    private final PortalRenderObjectVbo frameQuads = new PortalRenderObjectVbo(
            () -> "minihud-portal:portal_preview/frame",
            MaLiLibPipelines.POSITION_COLOR_MASA_NO_DEPTH_NO_CULL);
    private LongOpenHashSet frameBlocks = new LongOpenHashSet();
    @Nullable private PortalBounds lastPortalBounds;
    private boolean dirty = true;
    private boolean hasData;

    private PortalGhostRenderer()
    {
        this.useCulling = false;
    }

    @Override
    public String getName()
    {
        return "PortalPreviewFrame";
    }

    @Override
    public boolean shouldRender(MinecraftClient mc)
    {
        return mc.world != null && mc.player != null;
    }

    @Override
    public boolean needsUpdate(Entity entity, MinecraftClient mc)
    {
        return true;
    }

    @Override
    public void update(Vec3d cameraPos, Entity entity, MinecraftClient mc, Profiler profiler)
    {
        PortalLinkPreview.PlacementPreview preview = PortalLinkPreview.computePlacementPreview(mc);

        if (preview == null)
        {
            this.clearFrame();
            return;
        }

        PortalBounds bounds = preview.portalBounds();

        if (this.lastPortalBounds != null && this.lastPortalBounds.equals(bounds) &&
            this.dirty == false && this.frameQuads.isUploadedPublic())
        {
            return;
        }

        this.frameBlocks = preview.frameBlocks();
        this.lastPortalBounds = bounds;
        this.dirty = true;
    }

    @Override
    public void render(Vec3d cameraPos, MinecraftClient mc, Profiler profiler)
    {
        if (this.dirty)
        {
            this.buildFrameQuads(cameraPos);
        }
    }

    @Override
    public void draw(Vec3d cameraPos)
    {
        if (this.hasData == false)
        {
            return;
        }

        this.drawRenderObject(this.frameQuads, cameraPos);
    }

    @Override
    public boolean hasData()
    {
        return this.hasData && this.frameQuads.isUploadedPublic();
    }

    @Override
    public void reset()
    {
        super.reset();
        this.clearFrame();
    }

    @Override
    public void updateAll()
    {
        this.dirty = true;
    }

    @Override
    public void updateBetweenX(int minX, int maxX)
    {
        this.dirty = true;
    }

    @Override
    public void updateBetweenY(int minY, int maxY)
    {
        this.dirty = true;
    }

    @Override
    public void updateBetweenZ(int minZ, int maxZ)
    {
        this.dirty = true;
    }

    private void clearFrame()
    {
        this.frameBlocks.clear();
        this.lastPortalBounds = null;
        this.hasData = false;
        this.dirty = true;
    }

    private void buildFrameQuads(Vec3d cameraPos)
    {
        if (this.frameBlocks.isEmpty())
        {
            this.hasData = false;
            this.dirty = true;
            return;
        }

        BufferBuilder builder = this.frameQuads.start(
                () -> "minihud-portal:portal_preview/frame",
                MaLiLibPipelines.POSITION_COLOR_MASA_NO_DEPTH_NO_CULL);
        Color4f color = Color4f.fromColor(FRAME_COLOR, FRAME_ALPHA);

        RenderUtils.renderBlockPositions(this.frameBlocks, this.layerRange, color, 0.0D, cameraPos, builder);

        BuiltBuffer meshData = builder.endNullable();

        if (meshData != null)
        {
            this.frameQuads.upload(meshData, false);
            meshData.close();
            this.hasData = true;
            this.dirty = false;
        }
        else
        {
            this.hasData = false;
            this.dirty = true;
        }
    }

    private void drawRenderObject(PortalRenderObjectVbo obj, Vec3d cameraPos)
    {
        if (obj == null || obj.isStartedPublic() == false || obj.isUploadedPublic() == false)
        {
            return;
        }

        if (this.shouldResort && obj.shouldResortPublic())
        {
            obj.resortTranslucentPublic(obj.createVertexSorterPublic(cameraPos));
        }

        obj.drawPostPublic(false);
    }
}
