package ninja.trek.portal;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import fi.dy.masa.malilib.interfaces.IRangeChangeListener;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.minihud.renderer.OverlayRendererBase;
import fi.dy.masa.minihud.renderer.RenderObjectVbo;
import fi.dy.masa.minihud.renderer.RenderUtils;

public class PortalZoneRenderer extends OverlayRendererBase implements IRangeChangeListener
{
    public static final PortalZoneRenderer INSTANCE = new PortalZoneRenderer();

    private static final int PORTAL_RADIUS_BLOCKS = 256;
    private static final int MAX_CHUNKS_PER_TICK = 1;
    private static final short OUTSIDE_ZONE = Short.MIN_VALUE;
    private static final short NO_PORTAL = -1;

    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongOpenHashSet queuedChunks = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<ChunkBorderData> chunkData = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<LongOpenHashSet> positionsByColor = new Int2ObjectOpenHashMap<>();
    private final LayerRange layerRange = new LayerRange(this);

    private boolean needsFullRebuild = true;
    private boolean renderDirty = true;
    private boolean portalDataDirty = true;
    private boolean hasData;
    private boolean lastShowZoneBorders;
    private boolean lastRenderLines;
    private boolean lastRenderThrough;
    private BlockPos lastCenter = BlockPos.ORIGIN;
    private String lastDimensionId = "";
    private PortalSearchContext searchContext;

    private PortalZoneRenderer()
    {
        this.useCulling = false;
        PortalDataStore.getInstance().addListener(this::markDirty);
    }

    @Override
    public String getName()
    {
        return "PortalZoneBorders";
    }

    @Override
    public boolean shouldRender(MinecraftClient mc)
    {
        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        return settings.isShowZoneBorders() && mc.world != null && this.resolveTarget(mc.world) != null;
    }

    @Override
    public boolean needsUpdate(Entity entity, MinecraftClient mc)
    {
        if (this.needsFullRebuild || this.renderDirty || this.portalDataDirty)
        {
            return true;
        }

        if (this.queue.isEmpty() == false)
        {
            return true;
        }

        if (entity == null)
        {
            return false;
        }

        BlockPos center = entity.getBlockPos();
        return this.shouldRebuildForCenter(center, mc.world);
    }

    @Override
    public void update(Vec3d cameraPos, Entity entity, MinecraftClient mc, Profiler profiler)
    {
        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();

        if (settings.isShowZoneBorders() == false || mc.world == null || entity == null)
        {
            this.resetState();
            this.lastShowZoneBorders = settings.isShowZoneBorders();
            this.lastRenderLines = settings.shouldRenderLines();
            this.lastRenderThrough = settings.shouldRenderThrough();
            return;
        }

        World world = mc.world;
        TargetDimension target = this.resolveTarget(world);

        if (target == null)
        {
            this.resetState();
            this.lastShowZoneBorders = settings.isShowZoneBorders();
            this.lastRenderLines = settings.shouldRenderLines();
            this.lastRenderThrough = settings.shouldRenderThrough();
            return;
        }

        if (settings.isShowZoneBorders() != this.lastShowZoneBorders)
        {
            this.needsFullRebuild = true;
        }

        if (settings.shouldRenderLines() != this.lastRenderLines ||
            settings.shouldRenderThrough() != this.lastRenderThrough)
        {
            this.renderDirty = true;
        }

        BlockPos center = entity.getBlockPos();
        String dimensionId = world.getRegistryKey().getValue().toString();

        if (this.needsFullRebuild || this.portalDataDirty ||
            this.lastDimensionId.equals(dimensionId) == false ||
            this.shouldRebuildForCenter(center, world))
        {
            this.rebuild(center, world, target);
        }

        this.processQueue(world, center, target);

        if (this.hasData())
        {
            this.renderThrough = settings.shouldRenderThrough();
            this.render(cameraPos, mc, profiler, settings.shouldRenderLines());
        }

        this.renderDirty = false;
        this.portalDataDirty = false;
        this.needsFullRebuild = false;
        this.lastShowZoneBorders = settings.isShowZoneBorders();
        this.lastRenderLines = settings.shouldRenderLines();
        this.lastRenderThrough = settings.shouldRenderThrough();
    }

    @Override
    public boolean hasData()
    {
        return this.hasData && this.positionsByColor.isEmpty() == false;
    }

    @Override
    public void reset()
    {
        super.reset();
        this.resetState();
    }

    public void resetState()
    {
        this.queue.clear();
        this.queuedChunks.clear();
        this.chunkData.clear();
        this.positionsByColor.clear();
        this.searchContext = null;
        this.hasData = false;
        this.renderDirty = true;
        this.needsFullRebuild = true;
    }

    private void markDirty()
    {
        this.portalDataDirty = true;
        this.renderDirty = true;
    }

    public void onSettingsChanged()
    {
        this.renderDirty = true;
    }

    private void rebuild(BlockPos center, World world, TargetDimension target)
    {
        this.queue.clear();
        this.queuedChunks.clear();
        this.clearPositions();
        this.searchContext = this.buildSearchContext(center, world, target);
        this.lastCenter = center.toImmutable();
        this.lastDimensionId = world.getRegistryKey().getValue().toString();

        int minChunkX = (center.getX() - PORTAL_RADIUS_BLOCKS) >> 4;
        int maxChunkX = (center.getX() + PORTAL_RADIUS_BLOCKS) >> 4;
        int minChunkZ = (center.getZ() - PORTAL_RADIUS_BLOCKS) >> 4;
        int maxChunkZ = (center.getZ() + PORTAL_RADIUS_BLOCKS) >> 4;

        for (int cz = minChunkZ; cz <= maxChunkZ; ++cz)
        {
            for (int cx = minChunkX; cx <= maxChunkX; ++cx)
            {
                this.enqueueChunk(new ChunkPos(cx, cz));
            }
        }
    }

    private void processQueue(World world, BlockPos center, TargetDimension target)
    {
        if (this.searchContext == null || this.searchContext.portals.isEmpty())
        {
            this.hasData = false;
            return;
        }

        for (int i = 0; i < MAX_CHUNKS_PER_TICK && this.queue.isEmpty() == false; ++i)
        {
            long packed = this.queue.dequeueLong();
            this.queuedChunks.remove(packed);
            ChunkPos chunkPos = new ChunkPos(packed);

            WorldChunk chunk = (WorldChunk) world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false);

            if (chunk == null)
            {
                continue;
            }

            ChunkBorderData data = this.computeChunkBorders(center, chunkPos, target, this.searchContext);
            this.replaceChunkData(chunkPos, data);
        }

        this.hasData = this.positionsByColor.isEmpty() == false;
        this.renderDirty = true;
    }

    private void clearPositions()
    {
        for (Long2ObjectOpenHashMap.Entry<ChunkBorderData> entry : this.chunkData.long2ObjectEntrySet())
        {
            this.removeChunkPositions(entry.getValue());
        }

        this.chunkData.clear();
        this.positionsByColor.clear();
    }

    private void enqueueChunk(ChunkPos chunkPos)
    {
        long packed = chunkPos.toLong();

        if (this.queuedChunks.add(packed))
        {
            this.queue.enqueue(packed);
        }
    }

    private void replaceChunkData(ChunkPos chunkPos, ChunkBorderData data)
    {
        ChunkBorderData old = this.chunkData.put(chunkPos.toLong(), data);

        if (old != null)
        {
            this.removeChunkPositions(old);
        }

        this.addChunkPositions(data);
    }

    private void removeChunkPositions(ChunkBorderData data)
    {
        for (Long2IntMap.Entry entry : data.positions.long2IntEntrySet())
        {
            LongOpenHashSet set = this.positionsByColor.get(entry.getIntValue());

            if (set != null)
            {
                set.remove(entry.getLongKey());
            }
        }
    }

    private void addChunkPositions(ChunkBorderData data)
    {
        for (Long2IntMap.Entry entry : data.positions.long2IntEntrySet())
        {
            LongOpenHashSet set = this.positionsByColor.get(entry.getIntValue());

            if (set == null)
            {
                set = new LongOpenHashSet();
                this.positionsByColor.put(entry.getIntValue(), set);
            }

            set.add(entry.getLongKey());
        }
    }

    private boolean shouldRebuildForCenter(BlockPos center, @Nullable World world)
    {
        if (world == null)
        {
            return false;
        }

        int dx = Math.abs(center.getX() - this.lastCenter.getX());
        int dy = Math.abs(center.getY() - this.lastCenter.getY());
        int dz = Math.abs(center.getZ() - this.lastCenter.getZ());

        return dx > 8 || dy > 8 || dz > 8;
    }

    private ChunkBorderData computeChunkBorders(BlockPos center, ChunkPos chunkPos, TargetDimension target, PortalSearchContext context)
    {
        World world = context.world;
        int minY = world.getBottomY();
        int maxY = world.getTopYInclusive();
        int height = maxY - minY + 1;
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();

        short[] zones = new short[16 * 16 * height];
        for (int i = 0; i < zones.length; ++i)
        {
            zones[i] = OUTSIDE_ZONE;
        }

        for (int y = minY; y <= maxY; ++y)
        {
            if (Math.abs(y - center.getY()) > PORTAL_RADIUS_BLOCKS)
            {
                continue;
            }

            int yIndex = (y - minY) * 256;

            for (int z = 0; z < 16; ++z)
            {
                int worldZ = startZ + z;

                if (Math.abs(worldZ - center.getZ()) > PORTAL_RADIUS_BLOCKS)
                {
                    continue;
                }

                int zIndex = yIndex + (z * 16);

                for (int x = 0; x < 16; ++x)
                {
                    int worldX = startX + x;

                    if (Math.abs(worldX - center.getX()) > PORTAL_RADIUS_BLOCKS)
                    {
                        continue;
                    }

                    short zone = this.resolvePortalIndex(worldX, y, worldZ, target, context);
                    zones[zIndex + x] = zone;
                }
            }
        }

        ChunkBorderData data = new ChunkBorderData();

        for (int y = minY; y <= maxY; ++y)
        {
            if (Math.abs(y - center.getY()) > PORTAL_RADIUS_BLOCKS)
            {
                continue;
            }

            int yIndex = (y - minY) * 256;

            for (int z = 0; z < 16; ++z)
            {
                int worldZ = startZ + z;

                if (Math.abs(worldZ - center.getZ()) > PORTAL_RADIUS_BLOCKS)
                {
                    continue;
                }

                int zIndex = yIndex + (z * 16);

                for (int x = 0; x < 16; ++x)
                {
                    int worldX = startX + x;

                    if (Math.abs(worldX - center.getX()) > PORTAL_RADIUS_BLOCKS)
                    {
                        continue;
                    }

                    short zone = zones[zIndex + x];

                    if (zone == OUTSIDE_ZONE || zone == NO_PORTAL)
                    {
                        continue;
                    }

                    if (this.isBorderBlock(worldX, y, worldZ, zone, center, chunkPos, zones, target, context))
                    {
                        PortalCandidate portal = context.portals.get(zone);
                        data.positions.put(BlockPos.asLong(worldX, y, worldZ), portal.color());
                    }
                }
            }
        }

        return data;
    }

    private boolean isBorderBlock(int worldX, int worldY, int worldZ, short currentZone, BlockPos center,
                                  ChunkPos chunkPos, short[] zones, TargetDimension target,
                                  PortalSearchContext context)
    {
        return this.isDifferentZone(worldX + 1, worldY, worldZ, currentZone, center, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX - 1, worldY, worldZ, currentZone, center, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX, worldY + 1, worldZ, currentZone, center, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX, worldY - 1, worldZ, currentZone, center, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX, worldY, worldZ + 1, currentZone, center, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX, worldY, worldZ - 1, currentZone, center, chunkPos, zones, target, context);
    }

    private boolean isDifferentZone(int worldX, int worldY, int worldZ, short currentZone, BlockPos center,
                                    ChunkPos chunkPos, short[] zones, TargetDimension target,
                                    PortalSearchContext context)
    {
        short neighborZone;

        if (Math.abs(worldX - center.getX()) > PORTAL_RADIUS_BLOCKS ||
            Math.abs(worldY - center.getY()) > PORTAL_RADIUS_BLOCKS ||
            Math.abs(worldZ - center.getZ()) > PORTAL_RADIUS_BLOCKS)
        {
            neighborZone = NO_PORTAL;
        }
        else if ((worldX >> 4) == chunkPos.x && (worldZ >> 4) == chunkPos.z)
        {
            int minY = context.world.getBottomY();
            int maxY = context.world.getTopYInclusive();

            if (worldY < minY || worldY > maxY)
            {
                neighborZone = NO_PORTAL;
            }
            else
            {
                int yIndex = (worldY - minY) * 256;
                int zIndex = (worldZ & 15) * 16;
                neighborZone = zones[yIndex + zIndex + (worldX & 15)];
            }
        }
        else
        {
            neighborZone = this.resolvePortalIndex(worldX, worldY, worldZ, target, context);
        }

        return currentZone != neighborZone;
    }

    private short resolvePortalIndex(int worldX, int worldY, int worldZ, TargetDimension target, PortalSearchContext context)
    {
        int destX = context.clampX((worldX + 0.5D) * target.scale);
        int destZ = context.clampZ((worldZ + 0.5D) * target.scale);
        int destY = MathHelper.floor(worldY + 0.5D);

        int bestIndex = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        int bestY = Integer.MAX_VALUE;

        for (int i = 0; i < context.portals.size(); ++i)
        {
            PortalCandidate portal = context.portals.get(i);

            if (portal.isOutsideSearchSquare(destX, destZ, target.searchRadius))
            {
                continue;
            }

            PortalBounds bounds = portal.bounds();
            int closestX = MathHelper.clamp(destX, bounds.getMinX(), bounds.getMaxX());
            int closestY = MathHelper.clamp(destY, bounds.getMinY(), bounds.getMaxY());
            int closestZ = MathHelper.clamp(destZ, bounds.getMinZ(), bounds.getMaxZ());
            double dx = closestX - destX;
            double dy = closestY - destY;
            double dz = closestZ - destZ;
            double dist = dx * dx + dy * dy + dz * dz;

            if (dist < bestDist || (dist == bestDist && closestY < bestY))
            {
                bestDist = dist;
                bestY = closestY;
                bestIndex = i;
            }
        }

        return bestIndex == -1 ? NO_PORTAL : (short) bestIndex;
    }

    private PortalSearchContext buildSearchContext(BlockPos center, World world, TargetDimension target)
    {
        PortalSearchContext context = new PortalSearchContext(world);
        context.border = world.getWorldBorder();
        context.borderWest = context.border.getBoundWest();
        context.borderEast = context.border.getBoundEast() - 1.0E-5D;
        context.borderNorth = context.border.getBoundNorth();
        context.borderSouth = context.border.getBoundSouth() - 1.0E-5D;

        int minSourceX = center.getX() - PORTAL_RADIUS_BLOCKS;
        int maxSourceX = center.getX() + PORTAL_RADIUS_BLOCKS;
        int minSourceZ = center.getZ() - PORTAL_RADIUS_BLOCKS;
        int maxSourceZ = center.getZ() + PORTAL_RADIUS_BLOCKS;

        double minDestX = Math.min((minSourceX + 0.5D) * target.scale, (maxSourceX + 0.5D) * target.scale);
        double maxDestX = Math.max((minSourceX + 0.5D) * target.scale, (maxSourceX + 0.5D) * target.scale);
        double minDestZ = Math.min((minSourceZ + 0.5D) * target.scale, (maxSourceZ + 0.5D) * target.scale);
        double maxDestZ = Math.max((minSourceZ + 0.5D) * target.scale, (maxSourceZ + 0.5D) * target.scale);

        int destMinX = context.clampX(minDestX) - target.searchRadius;
        int destMaxX = context.clampX(maxDestX) + target.searchRadius;
        int destMinZ = context.clampZ(minDestZ) - target.searchRadius;
        int destMaxZ = context.clampZ(maxDestZ) + target.searchRadius;

        List<PortalCandidate> portals = new ArrayList<>();

        for (PortalEntry entry : PortalDataStore.getInstance().getPortals())
        {
            if (entry.getDimensionId().equals(target.dimensionId) == false)
            {
                continue;
            }

            PortalBounds bounds = entry.getBounds();

            if (bounds.getMaxX() < destMinX || bounds.getMinX() > destMaxX ||
                bounds.getMaxZ() < destMinZ || bounds.getMinZ() > destMaxZ)
            {
                continue;
            }

            portals.add(new PortalCandidate(bounds, entry.getColor()));
        }

        context.portals = portals;
        return context;
    }

    private void render(Vec3d cameraPos, MinecraftClient mc, Profiler profiler, boolean renderLines)
    {
        this.allocateBuffers(renderLines);
        this.renderQuads(cameraPos, mc, profiler);

        if (renderLines)
        {
            this.renderOutlines(cameraPos, mc, profiler);
        }
    }

    @Override
    public void render(Vec3d cameraPos, MinecraftClient mc, Profiler profiler)
    {
        boolean renderLines = PortalDataStore.getInstance().getZoneSettings().shouldRenderLines();
        this.render(cameraPos, mc, profiler, renderLines);
    }

    private void renderQuads(Vec3d cameraPos, MinecraftClient mc, Profiler profiler)
    {
        if (mc.world == null || mc.player == null)
        {
            return;
        }

        profiler.push("portal_zone_quads");
        RenderObjectVbo ctx = this.renderObjects.getFirst();
        BufferBuilder builder = ctx.start(() -> "minihud-portal:portal_zones/quads",
                this.renderThrough ? MaLiLibPipelines.MINIHUD_SHAPE_NO_DEPTH_OFFSET : MaLiLibPipelines.MINIHUD_SHAPE_OFFSET_NO_CULL);

        for (Int2ObjectOpenHashMap.Entry<LongOpenHashSet> entry : this.positionsByColor.int2ObjectEntrySet())
        {
            Color4f color = Color4f.fromColor(entry.getIntKey(), 1.0f);
            RenderUtils.renderBlockPositions(entry.getValue(), this.layerRange, color, 0.0D, cameraPos, builder);
        }

        try
        {
            BuiltBuffer meshData = builder.endNullable();

            if (meshData != null)
            {
                ctx.upload(meshData, this.shouldResort);

                if (this.shouldResort)
                {
                    ctx.startResorting(meshData, ctx.createVertexSorter(cameraPos));
                }

                meshData.close();
            }
        }
        catch (Exception ignore)
        {
        }

        profiler.pop();
    }

    private void renderOutlines(Vec3d cameraPos, MinecraftClient mc, Profiler profiler)
    {
        if (mc.world == null || mc.player == null)
        {
            return;
        }

        profiler.push("portal_zone_outlines");
        RenderObjectVbo ctx = this.renderObjects.get(1);
        BufferBuilder builder = ctx.start(() -> "minihud-portal:portal_zones/outlines",
                MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH);

        for (Int2ObjectOpenHashMap.Entry<LongOpenHashSet> entry : this.positionsByColor.int2ObjectEntrySet())
        {
            Color4f color = Color4f.fromColor(entry.getIntKey(), 1.0f);
            RenderUtils.renderBlockPositionOutlines(entry.getValue(), this.layerRange, color, 0.0D, cameraPos, builder);
        }

        try
        {
            BuiltBuffer meshData = builder.endNullable();

            if (meshData != null)
            {
                ctx.upload(meshData, false);
                meshData.close();
            }
        }
        catch (Exception ignore)
        {
        }

        profiler.pop();
    }

    @Override
    public void updateAll()
    {
        this.needsFullRebuild = true;
    }

    @Override
    public void updateBetweenX(int minX, int maxX)
    {
        this.needsFullRebuild = true;
    }

    @Override
    public void updateBetweenY(int minY, int maxY)
    {
        this.needsFullRebuild = true;
    }

    @Override
    public void updateBetweenZ(int minZ, int maxZ)
    {
        this.needsFullRebuild = true;
    }

    @Nullable
    private TargetDimension resolveTarget(World world)
    {
        String dimensionId = world.getRegistryKey().getValue().toString();

        if (dimensionId.equals(World.NETHER.getValue().toString()))
        {
            return new TargetDimension(World.OVERWORLD.getValue().toString(), 8.0D, 128);
        }

        if (dimensionId.equals(World.OVERWORLD.getValue().toString()))
        {
            return new TargetDimension(World.NETHER.getValue().toString(), 1.0D / 8.0D, 16);
        }

        return null;
    }

    private static class PortalSearchContext
    {
        private final World world;
        private WorldBorder border;
        private double borderWest;
        private double borderEast;
        private double borderNorth;
        private double borderSouth;
        private List<PortalCandidate> portals = List.of();

        private PortalSearchContext(World world)
        {
            this.world = world;
        }

        private int clampX(double x)
        {
            return MathHelper.floor(MathHelper.clamp(x, this.borderWest, this.borderEast));
        }

        private int clampZ(double z)
        {
            return MathHelper.floor(MathHelper.clamp(z, this.borderNorth, this.borderSouth));
        }
    }

    private record PortalCandidate(PortalBounds bounds, int color)
    {
        private int minX() { return this.bounds.getMinX(); }
        private int minY() { return this.bounds.getMinY(); }
        private int minZ() { return this.bounds.getMinZ(); }
        private int maxX() { return this.bounds.getMaxX(); }
        private int maxY() { return this.bounds.getMaxY(); }
        private int maxZ() { return this.bounds.getMaxZ(); }

        private boolean isOutsideSearchSquare(int destX, int destZ, int radius)
        {
            return this.maxX() < destX - radius || this.minX() > destX + radius ||
                   this.maxZ() < destZ - radius || this.minZ() > destZ + radius;
        }
    }

    private static class ChunkBorderData
    {
        private final Long2IntOpenHashMap positions = new Long2IntOpenHashMap();
    }

    private record TargetDimension(String dimensionId, double scale, int searchRadius)
    {
    }
}
