package ninja.trek.portal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
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

    private static final int MAX_GROUPS_PER_TICK = 1;
    private static final short NO_PORTAL = -1;

    private final List<PortalWorkGroup> pendingGroups = new ArrayList<>();
    private final Int2ObjectOpenHashMap<LongOpenHashSet> positionsByPortal = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<PortalRenderCache> portalRenderCaches = new Int2ObjectOpenHashMap<>();
    private final LayerRange layerRange = new LayerRange(this);

    private int nextGroupIndex;
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

        if (this.nextGroupIndex < this.pendingGroups.size())
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
            }
            else
            {
                this.pendingToggleDiagnostics = false;
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
            this.markAllPortalsDirty(true, true);
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

        this.processGroups(target);

        if (this.pendingToggleDiagnostics)
        {
            int portalCount = this.searchContext != null ? this.searchContext.portals.size() : 0;
            LOGGER.info(
                    "Portal zone borders diagnostics: rebuild={} renderDirty={} portalDataDirty={} queued={} portals={} positionsByPortal={}",
                    this.needsFullRebuild,
                    this.renderDirty,
                    this.portalDataDirty,
                    Math.max(0, this.pendingGroups.size() - this.nextGroupIndex),
                    portalCount,
                    this.positionsByPortal.size());
            this.pendingToggleDiagnostics = false;
        }

        if (showZoneBorders && this.hasData() == false &&
            this.nextGroupIndex >= this.pendingGroups.size() && this.loggedNoDataSinceToggle == false)
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
        this.pendingGroups.clear();
        this.nextGroupIndex = 0;
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
    }

    private void rebuild(World world, TargetDimension target)
    {
        this.clearPositions();
        this.searchContext = this.buildSearchContext(world, target);
        this.lastDimensionId = world.getRegistryKey().getValue().toString();

        if (this.searchContext == null || this.searchContext.portals.isEmpty())
        {
            return;
        }

        this.initializePortalRenderCaches();
        this.pendingGroups.clear();
        this.pendingGroups.addAll(this.buildWorkGroups(this.searchContext.influences));
        this.nextGroupIndex = 0;
    }

    private void processGroups(TargetDimension target)
    {
        if (this.searchContext == null || this.searchContext.portals.isEmpty())
        {
            this.hasData = false;
            return;
        }

        for (int i = 0; i < MAX_GROUPS_PER_TICK && this.nextGroupIndex < this.pendingGroups.size(); ++i)
        {
            PortalWorkGroup group = this.pendingGroups.get(this.nextGroupIndex);
            this.processGroup(group, target, this.searchContext);
            this.nextGroupIndex++;
        }

        this.hasData = this.positionsByPortal.isEmpty() == false;
        this.renderDirty = true;
    }

    private void clearPositions()
    {
        this.positionsByPortal.clear();
        this.clearPortalRenderCaches();
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

    private void processGroup(PortalWorkGroup group, TargetDimension target, PortalSearchContext context)
    {
        if (group.portalIndices.length == 1)
        {
            int portalIndex = group.portalIndices[0];
            PortalCandidate portal = context.portals.get(portalIndex);
            PortalInfluence influence = context.influences.get(portalIndex);
            this.processIsolatedPortal(portalIndex, portal, influence, target, context);
            this.markPortalDirty(portalIndex, true);
            return;
        }

        this.processOverlapGroup(group, target, context);
        for (int portalIndex : group.portalIndices)
        {
            this.markPortalDirty(portalIndex, true);
        }
    }

    private void processIsolatedPortal(int portalIndex, PortalCandidate portal, PortalInfluence influence,
                                       TargetDimension target, PortalSearchContext context)
    {
        int minY = influence.minY();
        int maxY = influence.maxY();
        int minX = influence.minX();
        int maxX = influence.maxX();
        int minZ = influence.minZ();
        int maxZ = influence.maxZ();

        for (int y = minY; y <= maxY; ++y)
        {
            for (int z = minZ; z <= maxZ; ++z)
            {
                for (int x = minX; x <= maxX; ++x)
                {
                    if (this.isWithinInfluence(x, y, z, portal, target, context) == false)
                    {
                        continue;
                    }

                    if (this.isBoundaryForPortal(x, y, z, portal, target, context))
                    {
                        this.addPortalPosition(this.positionsByPortal, portalIndex, BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
    }

    private void processOverlapGroup(PortalWorkGroup group, TargetDimension target, PortalSearchContext context)
    {
        PortalInfluence bounds = group.bounds;
        int minY = bounds.minY();
        int maxY = bounds.maxY();
        int minX = bounds.minX();
        int maxX = bounds.maxX();
        int minZ = bounds.minZ();
        int maxZ = bounds.maxZ();

        for (int y = minY; y <= maxY; ++y)
        {
            for (int z = minZ; z <= maxZ; ++z)
            {
                for (int x = minX; x <= maxX; ++x)
                {
                    short zone = this.resolvePortalIndex(x, y, z, target, context, group.portalIndices);

                    if (zone == NO_PORTAL)
                    {
                        continue;
                    }

                    if (this.isBoundaryInGroup(x, y, z, zone, target, context, group.portalIndices))
                    {
                        this.addPortalPosition(this.positionsByPortal, zone, BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
    }

    private boolean isBoundaryForPortal(int worldX, int worldY, int worldZ, PortalCandidate portal,
                                        TargetDimension target, PortalSearchContext context)
    {
        return this.isWithinInfluence(worldX + 1, worldY, worldZ, portal, target, context) == false ||
               this.isWithinInfluence(worldX - 1, worldY, worldZ, portal, target, context) == false ||
               this.isWithinInfluence(worldX, worldY + 1, worldZ, portal, target, context) == false ||
               this.isWithinInfluence(worldX, worldY - 1, worldZ, portal, target, context) == false ||
               this.isWithinInfluence(worldX, worldY, worldZ + 1, portal, target, context) == false ||
               this.isWithinInfluence(worldX, worldY, worldZ - 1, portal, target, context) == false;
    }

    private boolean isBoundaryInGroup(int worldX, int worldY, int worldZ, short currentZone,
                                      TargetDimension target, PortalSearchContext context, int[] portalIndices)
    {
        return this.resolvePortalIndex(worldX + 1, worldY, worldZ, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX - 1, worldY, worldZ, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX, worldY + 1, worldZ, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX, worldY - 1, worldZ, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX, worldY, worldZ + 1, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX, worldY, worldZ - 1, target, context, portalIndices) != currentZone;
    }

    private boolean isWithinInfluence(int worldX, int worldY, int worldZ, PortalCandidate portal,
                                      TargetDimension target, PortalSearchContext context)
    {
        if (worldY < context.world.getBottomY() || worldY > context.world.getTopYInclusive())
        {
            return false;
        }

        int destX = context.clampX((worldX + 0.5D) * target.scale);
        int destZ = context.clampZ((worldZ + 0.5D) * target.scale);
        int radius = target.searchRadius;

        if (portal.isOutsideSearchSquare(destX, destZ, radius))
        {
            return false;
        }

        return true;
    }

    private short resolvePortalIndex(int worldX, int worldY, int worldZ, TargetDimension target,
                                     PortalSearchContext context, int[] portalIndices)
    {
        if (worldY < context.world.getBottomY() || worldY > context.world.getTopYInclusive())
        {
            return NO_PORTAL;
        }

        int destX = context.clampX((worldX + 0.5D) * target.scale);
        int destZ = context.clampZ((worldZ + 0.5D) * target.scale);
        int destY = MathHelper.floor(worldY + 0.5D);
        int radius = target.searchRadius;

        int bestIndex = -1;
        double bestDist = Double.POSITIVE_INFINITY;

        for (int portalIndex : portalIndices)
        {
            PortalCandidate portal = context.portals.get(portalIndex);

            if (portal.isOutsideSearchSquare(destX, destZ, radius))
            {
                continue;
            }

            PortalBounds bounds = portal.bounds();
            int closestX = MathHelper.clamp(destX, bounds.getMinX(), bounds.getMaxX());
            int closestZ = MathHelper.clamp(destZ, bounds.getMinZ(), bounds.getMaxZ());
            int closestY = bounds.getMinY();
            double dx = closestX - destX;
            double dy = closestY - destY;
            double dz = closestZ - destZ;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < bestDist)
            {
                bestDist = distSq;
                bestIndex = portalIndex;
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
        context.influences = this.buildInfluences(context, target);
        return context;
    }

    private List<PortalInfluence> buildInfluences(PortalSearchContext context, TargetDimension target)
    {
        if (context.portals.isEmpty())
        {
            return List.of();
        }

        int worldMinY = context.world.getBottomY();
        int worldMaxY = context.world.getTopYInclusive();
        List<PortalInfluence> influences = new ArrayList<>(context.portals.size());

        for (PortalCandidate portal : context.portals)
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

            influences.add(new PortalInfluence(minX, maxX, worldMinY, worldMaxY, minZ, maxZ));
        }

        return influences;
    }

    private List<PortalWorkGroup> buildWorkGroups(List<PortalInfluence> influences)
    {
        if (influences.isEmpty())
        {
            return List.of();
        }

        int count = influences.size();
        boolean[] visited = new boolean[count];
        List<PortalWorkGroup> groups = new ArrayList<>();

        for (int i = 0; i < count; ++i)
        {
            if (visited[i])
            {
                continue;
            }

            IntOpenHashSet portalIndices = new IntOpenHashSet();
            PortalInfluence bounds = influences.get(i);
            ArrayDeque<Integer> stack = new ArrayDeque<>();
            stack.push(i);
            visited[i] = true;

            while (stack.isEmpty() == false)
            {
                int index = stack.pop();
                portalIndices.add(index);
                PortalInfluence influence = influences.get(index);
                bounds = PortalInfluence.union(bounds, influence);

                for (int j = 0; j < count; ++j)
                {
                    if (visited[j])
                    {
                        continue;
                    }

                    if (influence.intersects(influences.get(j)))
                    {
                        visited[j] = true;
                        stack.push(j);
                    }
                }
            }

            groups.add(new PortalWorkGroup(portalIndices.toIntArray(), bounds));
        }

        return groups;
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

        double maxRange = mc.options.getViewDistance().getValue() * 16.0D * 2.0D;
        double maxRangeSq = maxRange * maxRange;

        profiler.push(renderLines ? "portal_zone_lines" : "portal_zone_quads");
        for (Int2ObjectOpenHashMap.Entry<PortalRenderCache> entry : this.portalRenderCaches.int2ObjectEntrySet())
        {
            PortalRenderCache cache = entry.getValue();

            if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
            {
                continue;
            }

            if (renderLines)
            {
                this.buildPortalOutlines(cache, cameraPos);
            }
            else
            {
                this.buildPortalQuads(cache, cameraPos);
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
        Color4f color = Color4f.fromColor(cache.color, 0.3f);
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
        double maxRange = mc.options.getViewDistance().getValue() * 16.0D * 2.0D;
        double maxRangeSq = maxRange * maxRange;

        for (PortalRenderCache cache : this.portalRenderCaches.values())
        {
            if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
            {
                continue;
            }

            if (renderLines)
            {
                this.drawRenderObject(cache.outlines, cameraPos);
            }
            else
            {
                this.drawRenderObject(cache.quads, cameraPos);
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
        double maxRange = mc.options.getViewDistance().getValue() * 16.0D * 2.0D;
        double maxRangeSq = maxRange * maxRange;

        for (PortalRenderCache cache : this.portalRenderCaches.values())
        {
            if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
            {
                continue;
            }

            if (renderLines)
            {
                if (cache.outlinesDirty || cache.outlines.isUploadedPublic() == false)
                {
                    return true;
                }
            }
            else if (cache.quadsDirty || cache.quads.isUploadedPublic() == false)
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

    private record PortalInfluence(int minX, int maxX, int minY, int maxY, int minZ, int maxZ)
    {
        private boolean intersects(PortalInfluence other)
        {
            return this.maxX >= other.minX && this.minX <= other.maxX &&
                   this.maxY >= other.minY && this.minY <= other.maxY &&
                   this.maxZ >= other.minZ && this.minZ <= other.maxZ;
        }

        private double distanceSq2D(double x, double z)
        {
            double clampedX = Math.max(this.minX, Math.min(this.maxX, x));
            double clampedZ = Math.max(this.minZ, Math.min(this.maxZ, z));
            double dx = x - clampedX;
            double dz = z - clampedZ;
            return (dx * dx) + (dz * dz);
        }

        private static PortalInfluence union(PortalInfluence first, PortalInfluence second)
        {
            int minX = Math.min(first.minX, second.minX);
            int maxX = Math.max(first.maxX, second.maxX);
            int minY = Math.min(first.minY, second.minY);
            int maxY = Math.max(first.maxY, second.maxY);
            int minZ = Math.min(first.minZ, second.minZ);
            int maxZ = Math.max(first.maxZ, second.maxZ);
            return new PortalInfluence(minX, maxX, minY, maxY, minZ, maxZ);
        }
    }

    private static class PortalWorkGroup
    {
        private final int[] portalIndices;
        private final PortalInfluence bounds;

        private PortalWorkGroup(int[] portalIndices, PortalInfluence bounds)
        {
            this.portalIndices = portalIndices;
            this.bounds = bounds;
        }
    }

    private record TargetDimension(String dimensionId, double scale, int searchRadius)
    {
    }
}
