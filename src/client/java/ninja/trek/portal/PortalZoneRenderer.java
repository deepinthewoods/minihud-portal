package ninja.trek.portal;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
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
import fi.dy.masa.minihud.renderer.RenderUtils;

public class PortalZoneRenderer extends OverlayRendererBase implements IRangeChangeListener
{
    public static final PortalZoneRenderer INSTANCE = new PortalZoneRenderer();
    private static final Logger LOGGER = LogManager.getLogger("minihud-portal");

    private static final int MAX_CHUNKS_PER_TICK = 1;
    private static final short OUTSIDE_ZONE = Short.MIN_VALUE;
    private static final short NO_PORTAL = -1;

    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongOpenHashSet queuedChunks = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<ChunkBorderData> chunkData = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<LongOpenHashSet> positionsByPortal = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<PortalRenderCache> portalRenderCaches = new Int2ObjectOpenHashMap<>();
    private final LayerRange layerRange = new LayerRange(this);

    private boolean needsFullRebuild = true;
    private boolean renderDirty = true;
    private boolean portalDataDirty = true;
    private boolean hasData;
    private boolean lastShowZoneBorders;
    private boolean lastRenderLines;
    private boolean lastRenderThrough;
    private boolean loggedNoDataSinceToggle;
    private boolean loggedMissingTarget;
    private boolean pendingToggleDiagnostics;
    private boolean pendingChunkDiagnostics;
    private boolean loggedChunkNotLoaded;
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
        boolean showZoneBorders = settings.isShowZoneBorders();
        boolean hasWorld = mc.world != null;
        TargetDimension target = hasWorld ? this.resolveTarget(mc.world) : null;
        boolean shouldRender = showZoneBorders && hasWorld && target != null;

        if (this.pendingToggleDiagnostics)
        {
            LOGGER.info(
                    "Portal zone borders diagnostics: shouldRender={} showZoneBorders={} hasWorld={} targetDimension={}",
                    shouldRender,
                    showZoneBorders,
                    hasWorld,
                    target != null ? target.dimensionId : "<none>");
        }

        return shouldRender;
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

        if (mc.world == null)
        {
            return false;
        }

        Vec3d entityPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
        return this.hasVisibleDirtyPortals(entityPos, mc);
    }

    @Override
    public void update(Vec3d cameraPos, Entity entity, MinecraftClient mc, Profiler profiler)
    {
        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        boolean showZoneBorders = settings.isShowZoneBorders();

        if (showZoneBorders != this.lastShowZoneBorders)
        {
            LOGGER.info("Portal zone borders toggled {} (renderLines={}, renderThrough={})",
                    showZoneBorders ? "on" : "off",
                    settings.shouldRenderLines(),
                    settings.shouldRenderThrough());
            if (showZoneBorders)
            {
                this.loggedNoDataSinceToggle = false;
                this.loggedMissingTarget = false;
                this.pendingToggleDiagnostics = true;
                this.pendingChunkDiagnostics = true;
                this.loggedChunkNotLoaded = false;
            }
            else
            {
                this.pendingToggleDiagnostics = false;
                this.pendingChunkDiagnostics = false;
            }
        }

        if (showZoneBorders == false || mc.world == null || entity == null)
        {
            if (this.pendingToggleDiagnostics)
            {
                LOGGER.info("Portal zone borders diagnostics: render blocked (world={}, entity={})",
                        mc.world != null,
                        entity != null);
                this.pendingToggleDiagnostics = false;
            }
            if (showZoneBorders && this.loggedMissingTarget == false)
            {
                LOGGER.info("Portal zone borders render blocked (world={}, entity={})",
                        mc.world != null,
                        entity != null);
                this.loggedMissingTarget = true;
            }
            this.resetState();
            this.lastShowZoneBorders = showZoneBorders;
            this.lastRenderLines = settings.shouldRenderLines();
            this.lastRenderThrough = settings.shouldRenderThrough();
            return;
        }

        World world = mc.world;
        TargetDimension target = this.resolveTarget(world);

        if (target == null)
        {
            if (this.pendingToggleDiagnostics)
            {
                String dimensionId = world.getRegistryKey().getValue().toString();
                LOGGER.info("Portal zone borders diagnostics: render blocked (unsupported dimension={})", dimensionId);
                this.pendingToggleDiagnostics = false;
            }
            if (showZoneBorders && this.loggedMissingTarget == false)
            {
                String dimensionId = world.getRegistryKey().getValue().toString();
                LOGGER.info("Portal zone borders render blocked (unsupported dimension={})", dimensionId);
                this.loggedMissingTarget = true;
            }
            this.resetState();
            this.lastShowZoneBorders = showZoneBorders;
            this.lastRenderLines = settings.shouldRenderLines();
            this.lastRenderThrough = settings.shouldRenderThrough();
            return;
        }

        if (settings.isShowZoneBorders() != this.lastShowZoneBorders)
        {
            this.needsFullRebuild = true;
        }

        if (settings.shouldRenderLines() != this.lastRenderLines)
        {
            this.renderDirty = true;
            this.markAllPortalsDirty(false, true);
        }

        if (settings.shouldRenderThrough() != this.lastRenderThrough)
        {
            this.renderDirty = true;
            this.markAllPortalsDirty(true, false);
        }

        String dimensionId = world.getRegistryKey().getValue().toString();

        if (this.needsFullRebuild || this.portalDataDirty ||
            this.lastDimensionId.equals(dimensionId) == false)
        {
            this.rebuild(world, target);
        }

        this.processQueue(world, target);

        if (this.pendingToggleDiagnostics)
        {
            int portalCount = this.searchContext != null ? this.searchContext.portals.size() : 0;
            LOGGER.info(
                    "Portal zone borders diagnostics: rebuild={} renderDirty={} portalDataDirty={} queued={} portals={} positionsByPortal={}",
                    this.needsFullRebuild,
                    this.renderDirty,
                    this.portalDataDirty,
                    this.queue.size(),
                    portalCount,
                    this.positionsByPortal.size());
            this.pendingToggleDiagnostics = false;
        }

        if (showZoneBorders && this.hasData() == false && this.queue.isEmpty() && this.loggedNoDataSinceToggle == false)
        {
            int portalCount = this.searchContext != null ? this.searchContext.portals.size() : 0;
            LOGGER.info("Portal zone borders have no render data (portals={}, positionsByPortal={})",
                    portalCount,
                    this.positionsByPortal.size());
            this.loggedNoDataSinceToggle = true;
        }

        if (this.hasData())
        {
            this.renderThrough = settings.shouldRenderThrough();
            this.renderPortals(cameraPos, mc, profiler, settings.shouldRenderLines());
        }

        this.renderDirty = false;
        this.portalDataDirty = false;
        this.needsFullRebuild = false;
        this.lastShowZoneBorders = showZoneBorders;
        this.lastRenderLines = settings.shouldRenderLines();
        this.lastRenderThrough = settings.shouldRenderThrough();
    }

    @Override
    public boolean hasData()
    {
        return this.hasData && this.positionsByPortal.isEmpty() == false;
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
        this.positionsByPortal.clear();
        this.clearPortalRenderCaches();
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
        this.markAllPortalsDirty(true, true);
        boolean showZoneBorders = PortalDataStore.getInstance().getZoneSettings().isShowZoneBorders();
        this.pendingToggleDiagnostics = showZoneBorders;
        this.pendingChunkDiagnostics = showZoneBorders;
        this.loggedChunkNotLoaded = false;
    }

    private void rebuild(World world, TargetDimension target)
    {
        this.queue.clear();
        this.queuedChunks.clear();
        this.clearPositions();
        this.searchContext = this.buildSearchContext(world, target);
        this.lastDimensionId = world.getRegistryKey().getValue().toString();

        if (this.searchContext == null || this.searchContext.portals.isEmpty())
        {
            return;
        }

        this.initializePortalRenderCaches();

        for (PortalInfluence influence : this.searchContext.influences)
        {
            int minChunkX = influence.minX() >> 4;
            int maxChunkX = influence.maxX() >> 4;
            int minChunkZ = influence.minZ() >> 4;
            int maxChunkZ = influence.maxZ() >> 4;

            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz)
            {
                for (int cx = minChunkX; cx <= maxChunkX; ++cx)
                {
                    this.enqueueChunk(new ChunkPos(cx, cz));
                }
            }
        }
    }

    private void processQueue(World world, TargetDimension target)
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
                if (this.pendingChunkDiagnostics && this.loggedChunkNotLoaded == false)
                {
                    LOGGER.info("Portal zone borders diagnostics: chunk {} not loaded (status=FULL)", chunkPos);
                    this.loggedChunkNotLoaded = true;
                }
                continue;
            }

            if (this.searchContext.hasInfluence(chunkPos) == false)
            {
                continue;
            }

            ChunkBorderData data = this.computeChunkBorders(chunkPos, target, this.searchContext);
            if (this.pendingChunkDiagnostics)
            {
                IntOpenHashSet portals = new IntOpenHashSet();
                for (Int2ObjectOpenHashMap.Entry<LongOpenHashSet> entry : data.positions.int2ObjectEntrySet())
                {
                    portals.add(entry.getIntKey());
                }
                LOGGER.info(
                        "Portal zone borders diagnostics: chunk {} zoneBlocks={} borderBlocks={} borderPositions={} distinctPortals={}",
                        chunkPos,
                        data.zoneBlocks,
                        data.borderBlocks,
                        this.countPositions(data.positions),
                        portals.size());
                this.pendingChunkDiagnostics = false;
            }
            this.replaceChunkData(chunkPos, data);
        }

        this.hasData = this.positionsByPortal.isEmpty() == false;
        this.renderDirty = true;
    }

    private void clearPositions()
    {
        for (Long2ObjectOpenHashMap.Entry<ChunkBorderData> entry : this.chunkData.long2ObjectEntrySet())
        {
            this.removeChunkPositions(entry.getValue());
        }

        this.chunkData.clear();
        this.positionsByPortal.clear();
        this.clearPortalRenderCaches();
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
        for (Int2ObjectOpenHashMap.Entry<LongOpenHashSet> entry : data.positions.int2ObjectEntrySet())
        {
            LongOpenHashSet existing = this.positionsByPortal.get(entry.getIntKey());

            if (existing != null)
            {
                LongIterator iter = entry.getValue().iterator();
                while (iter.hasNext())
                {
                    existing.remove(iter.nextLong());
                }
            }

            this.markPortalDirty(entry.getIntKey(), true);
        }
    }

    private void addChunkPositions(ChunkBorderData data)
    {
        for (Int2ObjectOpenHashMap.Entry<LongOpenHashSet> entry : data.positions.int2ObjectEntrySet())
        {
            LongOpenHashSet existing = this.positionsByPortal.get(entry.getIntKey());

            if (existing == null)
            {
                existing = new LongOpenHashSet();
                this.positionsByPortal.put(entry.getIntKey(), existing);
            }

            LongIterator iter = entry.getValue().iterator();
            while (iter.hasNext())
            {
                existing.add(iter.nextLong());
            }

            this.markPortalDirty(entry.getIntKey(), true);
        }
    }

    private void addPortalPosition(Int2ObjectOpenHashMap<LongOpenHashSet> positions, int portalIndex, long pos)
    {
        LongOpenHashSet set = positions.get(portalIndex);

        if (set == null)
        {
            set = new LongOpenHashSet();
            positions.put(portalIndex, set);
        }

        set.add(pos);
    }

    private int countPositions(Int2ObjectOpenHashMap<LongOpenHashSet> positions)
    {
        int total = 0;

        for (Int2ObjectOpenHashMap.Entry<LongOpenHashSet> entry : positions.int2ObjectEntrySet())
        {
            total += entry.getValue().size();
        }

        return total;
    }

    private void initializePortalRenderCaches()
    {
        this.clearPortalRenderCaches();

        if (this.searchContext == null)
        {
            return;
        }

        for (int i = 0; i < this.searchContext.portals.size(); ++i)
        {
            PortalCandidate portal = this.searchContext.portals.get(i);
            PortalInfluence influence = this.searchContext.influences.size() > i ? this.searchContext.influences.get(i) : null;
            this.portalRenderCaches.put(i, new PortalRenderCache(i, portal.color(), influence));
        }
    }

    private void clearPortalRenderCaches()
    {
        for (PortalRenderCache cache : this.portalRenderCaches.values())
        {
            cache.close();
        }

        this.portalRenderCaches.clear();
    }

    private void markPortalDirty(int portalIndex, boolean markOutline)
    {
        PortalRenderCache cache = this.portalRenderCaches.get(portalIndex);

        if (cache != null)
        {
            cache.quadsDirty = true;

            if (markOutline)
            {
                cache.outlinesDirty = true;
            }
        }
    }

    private void markAllPortalsDirty(boolean quads, boolean outlines)
    {
        for (PortalRenderCache cache : this.portalRenderCaches.values())
        {
            if (quads)
            {
                cache.quadsDirty = true;
            }

            if (outlines)
            {
                cache.outlinesDirty = true;
            }
        }
    }

    private ChunkBorderData computeChunkBorders(ChunkPos chunkPos, TargetDimension target, PortalSearchContext context)
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

        int zoneBlocks = 0;

        for (int y = minY; y <= maxY; ++y)
        {
            int yIndex = (y - minY) * 256;

            for (int z = 0; z < 16; ++z)
            {
                int worldZ = startZ + z;

                int zIndex = yIndex + (z * 16);

                for (int x = 0; x < 16; ++x)
                {
                    int worldX = startX + x;

                    short zone = this.resolvePortalIndex(worldX, y, worldZ, target, context);
                    zones[zIndex + x] = zone;
                    if (zone != OUTSIDE_ZONE && zone != NO_PORTAL)
                    {
                        ++zoneBlocks;
                    }
                }
            }
        }

        ChunkBorderData data = new ChunkBorderData();
        int borderBlocks = 0;

        for (int y = minY; y <= maxY; ++y)
        {
            int yIndex = (y - minY) * 256;

            for (int z = 0; z < 16; ++z)
            {
                int worldZ = startZ + z;

                int zIndex = yIndex + (z * 16);

                for (int x = 0; x < 16; ++x)
                {
                    int worldX = startX + x;

                    short zone = zones[zIndex + x];

                    if (zone == OUTSIDE_ZONE || zone == NO_PORTAL)
                    {
                        continue;
                    }

                    if (this.isBorderBlock(worldX, y, worldZ, zone, chunkPos, zones, target, context))
                    {
                        this.addPortalPosition(data.positions, zone, BlockPos.asLong(worldX, y, worldZ));
                        ++borderBlocks;
                    }
                }
            }
        }

        data.zoneBlocks = zoneBlocks;
        data.borderBlocks = borderBlocks;
        return data;
    }

    private boolean isBorderBlock(int worldX, int worldY, int worldZ, short currentZone,
                                  ChunkPos chunkPos, short[] zones, TargetDimension target,
                                  PortalSearchContext context)
    {
        return this.isDifferentZone(worldX + 1, worldY, worldZ, currentZone, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX - 1, worldY, worldZ, currentZone, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX, worldY + 1, worldZ, currentZone, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX, worldY - 1, worldZ, currentZone, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX, worldY, worldZ + 1, currentZone, chunkPos, zones, target, context) ||
               this.isDifferentZone(worldX, worldY, worldZ - 1, currentZone, chunkPos, zones, target, context);
    }

    private boolean isDifferentZone(int worldX, int worldY, int worldZ, short currentZone,
                                    ChunkPos chunkPos, short[] zones, TargetDimension target,
                                    PortalSearchContext context)
    {
        short neighborZone;

        if ((worldX >> 4) == chunkPos.x && (worldZ >> 4) == chunkPos.z)
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

    private PortalSearchContext buildSearchContext(World world, TargetDimension target)
    {
        PortalSearchContext context = new PortalSearchContext(world);
        context.border = world.getWorldBorder();
        context.borderWest = context.border.getBoundWest();
        context.borderEast = context.border.getBoundEast() - 1.0E-5D;
        context.borderNorth = context.border.getBoundNorth();
        context.borderSouth = context.border.getBoundSouth() - 1.0E-5D;

        List<PortalCandidate> portals = new ArrayList<>();

        for (PortalEntry entry : PortalDataStore.getInstance().getPortals())
        {
            if (entry.getDimensionId().equals(target.dimensionId) == false)
            {
                continue;
            }

            PortalBounds bounds = entry.getBounds();

            portals.add(new PortalCandidate(bounds, entry.getColor()));
        }

        context.portals = portals;
        context.influences = this.buildInfluences(context.portals, target);
        return context;
    }

    private List<PortalInfluence> buildInfluences(List<PortalCandidate> portals, TargetDimension target)
    {
        if (portals.isEmpty())
        {
            return List.of();
        }

        List<PortalInfluence> influences = new ArrayList<>(portals.size());

        for (PortalCandidate portal : portals)
        {
            PortalBounds bounds = portal.bounds();
            double minDestX = bounds.getMinX() - target.searchRadius;
            double maxDestX = bounds.getMaxX() + target.searchRadius;
            double minDestZ = bounds.getMinZ() - target.searchRadius;
            double maxDestZ = bounds.getMaxZ() + target.searchRadius;

            int minSourceX = this.toSourceMin(minDestX, target.scale);
            int maxSourceX = this.toSourceMax(maxDestX, target.scale);
            int minSourceZ = this.toSourceMin(minDestZ, target.scale);
            int maxSourceZ = this.toSourceMax(maxDestZ, target.scale);

            int minX = Math.min(minSourceX, maxSourceX);
            int maxX = Math.max(minSourceX, maxSourceX);
            int minZ = Math.min(minSourceZ, maxSourceZ);
            int maxZ = Math.max(minSourceZ, maxSourceZ);

            influences.add(new PortalInfluence(minX, maxX, minZ, maxZ));
        }

        return influences;
    }

    private int toSourceMin(double dest, double scale)
    {
        return (int) Math.ceil(dest / scale - 0.5D);
    }

    private int toSourceMax(double dest, double scale)
    {
        return (int) Math.floor(dest / scale - 0.5D);
    }

    private void renderPortals(Vec3d cameraPos, MinecraftClient mc, Profiler profiler, boolean renderLines)
    {
        if (mc.world == null || mc.player == null)
        {
            return;
        }

        double maxRange = mc.options.getViewDistance().getValue() * 16.0D;
        double maxRangeSq = maxRange * maxRange;

        profiler.push("portal_zone_quads");
        for (Int2ObjectOpenHashMap.Entry<PortalRenderCache> entry : this.portalRenderCaches.int2ObjectEntrySet())
        {
            PortalRenderCache cache = entry.getValue();

            if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
            {
                continue;
            }

            this.buildPortalQuads(cache, cameraPos);

            if (renderLines)
            {
                this.buildPortalOutlines(cache, cameraPos);
            }
        }
        profiler.pop();
    }

    private void buildPortalQuads(PortalRenderCache cache, Vec3d cameraPos)
    {
        LongOpenHashSet positions = this.positionsByPortal.get(cache.portalIndex);

        if (positions == null || positions.isEmpty())
        {
            cache.resetIfUploaded();
            return;
        }

        if (cache.quadsDirty == false && cache.quads.isUploadedPublic())
        {
            return;
        }

        BufferBuilder builder = cache.quads.start(
                () -> "minihud-portal:portal_zones/quads/" + cache.portalIndex,
                this.renderThrough ? MaLiLibPipelines.MINIHUD_SHAPE_NO_DEPTH_OFFSET : MaLiLibPipelines.MINIHUD_SHAPE_OFFSET_NO_CULL);
        Color4f color = Color4f.fromColor(cache.color, 1.0f);
        RenderUtils.renderBlockPositions(positions, this.layerRange, color, 0.0D, cameraPos, builder);

        try
        {
            BuiltBuffer meshData = builder.endNullable();

            if (meshData != null)
            {
                cache.quads.upload(meshData, this.shouldResort);

                if (this.shouldResort)
                {
                    cache.quads.startResorting(meshData, cache.quads.createVertexSorterPublic(cameraPos));
                }

                meshData.close();
            }
        }
        catch (Exception ignore)
        {
        }

        cache.quadsDirty = false;
    }

    private void buildPortalOutlines(PortalRenderCache cache, Vec3d cameraPos)
    {
        LongOpenHashSet positions = this.positionsByPortal.get(cache.portalIndex);

        if (positions == null || positions.isEmpty())
        {
            cache.resetIfUploaded();
            return;
        }

        if (cache.outlinesDirty == false && cache.outlines.isUploadedPublic())
        {
            return;
        }

        BufferBuilder builder = cache.outlines.start(
                () -> "minihud-portal:portal_zones/outlines/" + cache.portalIndex,
                MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH);
        Color4f color = Color4f.fromColor(cache.color, 1.0f);
        RenderUtils.renderBlockPositionOutlines(positions, this.layerRange, color, 0.0D, cameraPos, builder);

        try
        {
            BuiltBuffer meshData = builder.endNullable();

            if (meshData != null)
            {
                cache.outlines.upload(meshData, false);
                meshData.close();
            }
        }
        catch (Exception ignore)
        {
        }

        cache.outlinesDirty = false;
    }

    @Override
    public void render(Vec3d cameraPos, MinecraftClient mc, Profiler profiler)
    {
        boolean renderLines = PortalDataStore.getInstance().getZoneSettings().shouldRenderLines();
        this.renderPortals(cameraPos, mc, profiler, renderLines);
    }

    @Override
    public void draw(Vec3d cameraPos)
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.world == null)
        {
            return;
        }

        boolean renderLines = PortalDataStore.getInstance().getZoneSettings().shouldRenderLines();
        double maxRange = mc.options.getViewDistance().getValue() * 16.0D;
        double maxRangeSq = maxRange * maxRange;

        for (PortalRenderCache cache : this.portalRenderCaches.values())
        {
            if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
            {
                continue;
            }

            this.drawRenderObject(cache.quads, cameraPos);

            if (renderLines)
            {
                this.drawRenderObject(cache.outlines, cameraPos);
            }
        }
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

        boolean setLineWidth = obj.getDrawMode() == com.mojang.blaze3d.vertex.VertexFormat.DrawMode.LINES ||
                               obj.getDrawMode() == com.mojang.blaze3d.vertex.VertexFormat.DrawMode.DEBUG_LINES;

        if (setLineWidth)
        {
            obj.lineWidthPublic(this.glLineWidth);
        }

        obj.drawPostPublic(setLineWidth);
    }

    private boolean hasVisibleDirtyPortals(Vec3d cameraPos, MinecraftClient mc)
    {
        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        boolean renderLines = settings.shouldRenderLines();
        double maxRange = mc.options.getViewDistance().getValue() * 16.0D;
        double maxRangeSq = maxRange * maxRange;

        for (PortalRenderCache cache : this.portalRenderCaches.values())
        {
            if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
            {
                continue;
            }

            if (cache.quadsDirty || cache.quads.isUploadedPublic() == false)
            {
                return true;
            }

            if (renderLines && (cache.outlinesDirty || cache.outlines.isUploadedPublic() == false))
            {
                return true;
            }
        }

        return false;
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
        private List<PortalInfluence> influences = List.of();

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

        private boolean hasInfluence(ChunkPos chunkPos)
        {
            if (this.influences.isEmpty())
            {
                return false;
            }

            int chunkMinX = chunkPos.getStartX();
            int chunkMinZ = chunkPos.getStartZ();
            int chunkMaxX = chunkMinX + 15;
            int chunkMaxZ = chunkMinZ + 15;

            for (PortalInfluence influence : this.influences)
            {
                if (influence.intersects(chunkMinX, chunkMaxX, chunkMinZ, chunkMaxZ))
                {
                    return true;
                }
            }

            return false;
        }
    }

    private static class PortalRenderCache
    {
        private final int portalIndex;
        private final int color;
        private final PortalInfluence influence;
        private PortalRenderObjectVbo quads;
        private PortalRenderObjectVbo outlines;
        private boolean quadsDirty = true;
        private boolean outlinesDirty = true;

        private PortalRenderCache(int portalIndex, int color, PortalInfluence influence)
        {
            this.portalIndex = portalIndex;
            this.color = color;
            this.influence = influence;
            this.quads = this.createQuads();
            this.outlines = this.createOutlines();
        }

        private PortalRenderObjectVbo createQuads()
        {
            return new PortalRenderObjectVbo(
                    () -> "minihud-portal:portal_zones/quads/" + this.portalIndex,
                    MaLiLibPipelines.MINIHUD_SHAPE_OFFSET_NO_CULL);
        }

        private PortalRenderObjectVbo createOutlines()
        {
            return new PortalRenderObjectVbo(
                    () -> "minihud-portal:portal_zones/outlines/" + this.portalIndex,
                    MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH);
        }

        private boolean isInRange(Vec3d cameraPos, double maxRangeSq)
        {
            if (this.influence == null)
            {
                return true;
            }

            return this.influence.distanceSq2D(cameraPos.x, cameraPos.z) <= maxRangeSq;
        }

        private void resetIfUploaded()
        {
            if (this.quads.isUploadedPublic() || this.outlines.isUploadedPublic())
            {
                this.resetBuffers();
            }
        }

        private void resetBuffers()
        {
            this.quads.closePublic();
            this.outlines.closePublic();
            this.quads = this.createQuads();
            this.outlines = this.createOutlines();
            this.quadsDirty = true;
            this.outlinesDirty = true;
        }

        private void close()
        {
            this.quads.closePublic();
            this.outlines.closePublic();
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

    private record PortalInfluence(int minX, int maxX, int minZ, int maxZ)
    {
        private boolean intersects(int chunkMinX, int chunkMaxX, int chunkMinZ, int chunkMaxZ)
        {
            return this.maxX >= chunkMinX && this.minX <= chunkMaxX &&
                   this.maxZ >= chunkMinZ && this.minZ <= chunkMaxZ;
        }

        private double distanceSq2D(double x, double z)
        {
            double clampedX = Math.max(this.minX, Math.min(this.maxX, x));
            double clampedZ = Math.max(this.minZ, Math.min(this.maxZ, z));
            double dx = x - clampedX;
            double dz = z - clampedZ;
            return (dx * dx) + (dz * dz);
        }
    }

    private static class ChunkBorderData
    {
        private final Int2ObjectOpenHashMap<LongOpenHashSet> positions = new Int2ObjectOpenHashMap<>();
        private int zoneBlocks;
        private int borderBlocks;
    }

    private record TargetDimension(String dimensionId, double scale, int searchRadius)
    {
    }
}
