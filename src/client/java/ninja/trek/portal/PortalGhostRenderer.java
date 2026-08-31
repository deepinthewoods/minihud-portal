package ninja.trek.portal;

import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import fi.dy.masa.malilib.util.position.Vec3d;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import fi.dy.masa.malilib.interfaces.IRangeChangeListener;
import fi.dy.masa.malilib.util.position.LayerRange;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.minihud.renderer.OverlayRendererBase;
import fi.dy.masa.minihud.renderer.RenderUtils;

public class PortalGhostRenderer extends OverlayRendererBase implements IRangeChangeListener
{
    public static final PortalGhostRenderer INSTANCE = new PortalGhostRenderer();
    private static final int FRAME_COLOR = 0xFFFFFF;
    private static final float FRAME_ALPHA = 0.4f;

    private final LayerRange layerRange = new LayerRange(this);
    @Nullable private PortalRenderObjectVbo frameQuads;
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
    public boolean shouldRender(Minecraft mc)
    {
        return mc.level != null && mc.player != null;
    }

    @Override
    public boolean needsUpdate(Entity entity, Minecraft mc)
    {
        return true;
    }

    @Override
    public void update(Vec3d cameraPos, Entity entity, Minecraft mc, ProfilerFiller profiler)
    {
        PortalLinkPreview.PlacementPreview preview = PortalLinkPreview.computePlacementPreview(mc);

        if (preview == null)
        {
            this.clearFrame();
            return;
        }

        PortalBounds bounds = preview.portalBounds();

        if (this.lastPortalBounds != null && this.lastPortalBounds.equals(bounds) &&
            this.dirty == false && this.frameQuads != null && this.frameQuads.isUploadedPublic())
        {
            return;
        }

        this.frameBlocks = preview.frameBlocks();
        this.lastPortalBounds = bounds;
        this.dirty = true;
    }

    @Override
    public void render(Vec3d cameraPos, Minecraft mc, ProfilerFiller profiler)
    {
        if (this.dirty)
        {
            this.buildFrameQuads(cameraPos);
        }
    }

    @Override
    public void draw(Vec3d cameraPos)
    {
        if (this.hasData == false || this.frameQuads == null)
        {
            return;
        }

        this.drawRenderObject(this.frameQuads, cameraPos);
    }

    @Override
    public boolean hasData()
    {
        return this.hasData && this.frameQuads != null && this.frameQuads.isUploadedPublic();
    }

    @Override
    public void reset()
    {
        super.reset();

        if (this.frameQuads != null)
        {
            this.frameQuads.closePublic();
            this.frameQuads = null;
        }

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

        if (this.frameQuads == null)
        {
            // MaLiLib's render pipelines are populated after Fabric entrypoints run.
            // Allocate the VBO on first use instead of during static initialization.
            this.frameQuads = new PortalRenderObjectVbo(
                    () -> "minihud-portal:portal_preview/frame",
                    MaLiLibPipelines.POSITION_COLOR_MASA_NO_DEPTH_NO_CULL);
        }

        BufferBuilder builder = this.frameQuads.start(
                () -> "minihud-portal:portal_preview/frame",
                MaLiLibPipelines.POSITION_COLOR_MASA_NO_DEPTH_NO_CULL,
                0);
        Color4f color = Color4f.fromColor(FRAME_COLOR, FRAME_ALPHA);

        RenderUtils.renderBlockPositions(this.frameBlocks, this.layerRange, color, 0.0D, cameraPos, builder);

        MeshData meshData = builder.build();

        if (meshData != null)
        {
            this.frameQuads.uploadAtOrigin(meshData, false, cameraPos);
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
        if (obj == null)
        {
            return;
        }

        obj.drawAtBuildOrigin(this.getUpdatePosition(), cameraPos, this.shouldResort);
    }
}
