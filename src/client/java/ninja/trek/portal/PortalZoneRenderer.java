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
        boolean renderLetters = settings.shouldRenderLetters();
        boolean hasWorld = mc.world != null;
        TargetDimension target = hasWorld ? this.resolveTarget(mc.world) : null;
        boolean shouldRender = (showZoneBorders || renderLetters) && hasWorld && target != null;

        if (this.pendingToggleDiagnostics)
        {
            LOGGER.info(
                    "Portal zone borders diagnostics: shouldRender={} showZoneBorders={} renderLetters={} hasWorld={} targetDimension={}",
                    shouldRender,
                    showZoneBorders,
                    renderLetters,
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
        boolean renderLetters = settings.shouldRenderLetters();

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

        if (showZoneBorders == false && renderLetters == false || mc.world == null || entity == null)
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

        if (this.hasData() || (renderLetters && this.searchContext != null && this.searchContext.portals.isEmpty() == false))
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
            cache.lettersDirty = true;

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

            cache.lettersDirty = true;

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
            int portalMinY = bounds.getMinY();

            // Check every portal block in the XZ plane (only bottom of each column matters per algorithm.txt)
            for (int portalX = bounds.getMinX(); portalX <= bounds.getMaxX(); ++portalX)
            {
                for (int portalZ = bounds.getMinZ(); portalZ <= bounds.getMaxZ(); ++portalZ)
                {
                    double dx = portalX - destX;
                    double dy = portalMinY - destY;
                    double dz = portalZ - destZ;
                    double distSq = dx * dx + dy * dy + dz * dz;

                    if (distSq < bestDist)
                    {
                        bestDist = distSq;
                        bestIndex = portalIndex;
                    }
                }
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

            portals.add(new PortalCandidate(bounds, entry.getColor(), entry.getDimensionId()));
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
        double upper = (dest + 1.0D) / scale - 0.5D;
        upper = Math.nextAfter(upper, Double.NEGATIVE_INFINITY);
        return (int) Math.floor(upper);
    }

    private void renderPortals(Vec3d cameraPos, MinecraftClient mc, Profiler profiler, boolean renderLines)
    {
        if (mc.world == null || mc.player == null)
        {
            return;
        }

        World world = mc.world;
        TargetDimension target = this.resolveTarget(world);
        if (target == null || this.searchContext == null)
        {
            return;
        }

        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        boolean showZoneBorders = settings.isShowZoneBorders();
        boolean renderLetters = settings.shouldRenderLetters();

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

            if (showZoneBorders)
            {
                if (renderLines)
                {
                    this.buildPortalOutlines(cache, cameraPos);
                }
                else
                {
                    this.buildPortalQuads(cache, cameraPos);
                }
            }

            // Build letters for this portal if enabled
            if (renderLetters)
            {
                PortalCandidate portal = this.searchContext.portals.get(cache.portalIndex);
                if (portal != null)
                {
                    this.buildPortalLetters(cache, cameraPos, portal, target);
                }
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
                this.renderThrough ? MaLiLibPipelines.POSITION_COLOR_MASA_NO_DEPTH_NO_CULL : MaLiLibPipelines.POSITION_COLOR_MASA_LEQUAL_DEPTH_OFFSET_1);
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

    private void buildPortalLetters(PortalRenderCache cache, Vec3d cameraPos, PortalCandidate portal, TargetDimension target)
    {
        if (cache.lettersDirty == false && cache.letters.isUploadedPublic())
        {
            return;
        }

        BufferBuilder builder = cache.letters.start(
                () -> "minihud-portal:portal_zones/letters/" + cache.portalIndex,
                MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL);  // No depth test - renders through walls

        // Calculate portal center (portal bounds are in target dimension coordinates)
        double centerX = (portal.bounds().getMinX() + portal.bounds().getMaxX()) / 2.0;
        double centerY = (portal.bounds().getMinY() + portal.bounds().getMaxY()) / 2.0;
        double centerZ = (portal.bounds().getMinZ() + portal.bounds().getMaxZ()) / 2.0;

        // Translate to source dimension position (inverse of the scale)
        double translatedCenterX = centerX / target.scale;
        double translatedCenterZ = centerZ / target.scale;

        // Calculate letter size based on portal's actual size (not scaled)
        double portalWidth = portal.bounds().getMaxX() - portal.bounds().getMinX() + 1.0;
        double portalHeight = portal.bounds().getMaxY() - portal.bounds().getMinY() + 1.0;
        double letterSize = Math.max(portalWidth, portalHeight);

        // Determine letter based on portal's own dimension
        boolean isNether = portal.dimensionId().equals(World.NETHER.getValue().toString());

        char letterChar = isNether ? 'N' : 'O';
        LOGGER.info("Building letter '{}' for portal {} at ({}, {}, {}), size={}, portalDim={}",
                   letterChar, cache.portalIndex, translatedCenterX, centerY, translatedCenterZ,
                   letterSize, portal.dimensionId());

        Color4f color = Color4f.fromColor(cache.color, 1.0f);
        this.drawBillboardedLetter(builder, translatedCenterX, centerY, translatedCenterZ,
                                   letterSize, letterChar, color, cameraPos);

        try
        {
            BuiltBuffer meshData = builder.endNullable();

            if (meshData != null)
            {
                cache.letters.upload(meshData, false);
                meshData.close();
                LOGGER.info("Uploaded letter mesh for portal {}", cache.portalIndex);
            }
            else
            {
                LOGGER.warn("Failed to build letter mesh for portal {} - meshData is null", cache.portalIndex);
            }
        }
        catch (Exception e)
        {
            LOGGER.error("Error building letter mesh for portal {}", cache.portalIndex, e);
        }

        cache.lettersDirty = false;
    }

    private void drawBillboardedLetter(BufferBuilder builder, double worldX, double worldY, double worldZ,
                                       double size, char letter, Color4f color, Vec3d cameraPos)
    {
        // Camera-relative position
        float cx = (float) (worldX - cameraPos.x);
        float cy = (float) (worldY - cameraPos.y);
        float cz = (float) (worldZ - cameraPos.z);
        float halfSize = (float) (size / 2.0);

        // For proper billboarding, we need to construct a coordinate system
        // where the letter faces the camera.
        // View direction: from letter to camera (negative of camera-relative position)
        float viewX = -cx;
        float viewY = -cy;
        float viewZ = -cz;
        float viewDist = (float) Math.sqrt(viewX * viewX + viewY * viewY + viewZ * viewZ);

        if (viewDist < 0.0001f)
        {
            // Camera is at the letter position, use default orientation
            viewX = 0; viewY = 0; viewZ = 1;
            viewDist = 1;
        }

        // Normalize view direction
        viewX /= viewDist;
        viewY /= viewDist;
        viewZ /= viewDist;

        // World up vector
        float worldUpX = 0, worldUpY = 1, worldUpZ = 0;

        // Right vector = world_up x view_dir (cross product)
        float rightX = worldUpY * viewZ - worldUpZ * viewY;
        float rightY = worldUpZ * viewX - worldUpX * viewZ;
        float rightZ = worldUpX * viewY - worldUpY * viewX;

        // If view direction is nearly parallel to world up, use alternate right vector
        float rightLen = (float) Math.sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ);
        if (rightLen < 0.001f)
        {
            rightX = 1; rightY = 0; rightZ = 0;
            rightLen = 1;
        }
        else
        {
            rightX /= rightLen;
            rightY /= rightLen;
            rightZ /= rightLen;
        }

        // Up vector = view_dir x right
        float upX = viewY * rightZ - viewZ * rightY;
        float upY = viewZ * rightX - viewX * rightZ;
        float upZ = viewX * rightY - viewY * rightX;

        if (letter == 'N')
        {
            // Draw N letter using billboarded coordinate system
            // Left vertical line position
            float leftX = cx - rightX * halfSize;
            float leftZ = cz - rightZ * halfSize;

            // Right vertical line position
            float rightVertX = cx + rightX * halfSize;
            float rightVertZ = cz + rightZ * halfSize;

            // Bottom and top positions along up vector
            float bottomY = cy - upY * halfSize;
            float topY = cy + upY * halfSize;

            // Draw multiple parallel lines for thickness
            int thickness = 8;
            for (int t = 0; t < thickness; t++)
            {
                float offset = (t / (float) (thickness - 1) - 0.5f) * 0.15f;
                float ox = rightX * offset;
                float oy = rightY * offset;
                float oz = rightZ * offset;

                // Left vertical line
                builder.vertex(leftX + ox, bottomY + oy, leftZ + oz).color(color.r, color.g, color.b, color.a);
                builder.vertex(leftX + ox, topY + oy, leftZ + oz).color(color.r, color.g, color.b, color.a);

                // Right vertical line
                builder.vertex(rightVertX + ox, bottomY + oy, rightVertZ + oz).color(color.r, color.g, color.b, color.a);
                builder.vertex(rightVertX + ox, topY + oy, rightVertZ + oz).color(color.r, color.g, color.b, color.a);

                // Diagonal line
                builder.vertex(leftX + ox, topY + oy, leftZ + oz).color(color.r, color.g, color.b, color.a);
                builder.vertex(rightVertX + ox, bottomY + oy, rightVertZ + oz).color(color.r, color.g, color.b, color.a);
            }
        }
        else if (letter == 'O')
        {
            // Draw O letter as an octagon with thickness
            int thickness = 8;
            for (int i = 0; i < 8; i++)
            {
                float angle1 = (float) ((i * Math.PI) / 4.0);
                float angle2 = (float) (((i + 1) * Math.PI) / 4.0);

                float cos1 = (float) Math.cos(angle1);
                float sin1 = (float) Math.sin(angle1);
                float cos2 = (float) Math.cos(angle2);
                float sin2 = (float) Math.sin(angle2);

                for (int t = 0; t < thickness; t++)
                {
                    float offset = (t / (float) (thickness - 1) - 0.5f) * 0.15f;
                    float ox = rightX * offset;
                    float oy = rightY * offset;
                    float oz = rightZ * offset;

                    float x1 = cx + (cos1 * rightX + sin1 * upX) * halfSize;
                    float y1 = cy + (cos1 * rightY + sin1 * upY) * halfSize;
                    float z1 = cz + (cos1 * rightZ + sin1 * upZ) * halfSize;

                    float x2 = cx + (cos2 * rightX + sin2 * upX) * halfSize;
                    float y2 = cy + (cos2 * rightY + sin2 * upY) * halfSize;
                    float z2 = cz + (cos2 * rightZ + sin2 * upZ) * halfSize;

                    builder.vertex(x1 + ox, y1 + oy, z1 + oz).color(color.r, color.g, color.b, color.a);
                    builder.vertex(x2 + ox, y2 + oy, z2 + oz).color(color.r, color.g, color.b, color.a);
                }
            }
        }
    }

    @Override
    public void render(Vec3d cameraPos, MinecraftClient mc, Profiler profiler)
    {
        boolean renderLines = PortalDataStore.getInstance().getZoneSettings().shouldRenderLines();
        boolean renderLetters = PortalDataStore.getInstance().getZoneSettings().shouldRenderLetters();

        // Mark letters as dirty every frame so they rebuild with current camera position for billboarding
        if (renderLetters)
        {
            for (PortalRenderCache cache : this.portalRenderCaches.values())
            {
                if (cache != null)
                {
                    cache.lettersDirty = true;
                }
            }
        }

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

        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        boolean showZoneBorders = settings.isShowZoneBorders();
        boolean renderLines = settings.shouldRenderLines();
        boolean renderLetters = settings.shouldRenderLetters();
        double maxRange = mc.options.getViewDistance().getValue() * 16.0D * 2.0D;
        double maxRangeSq = maxRange * maxRange;

        for (PortalRenderCache cache : this.portalRenderCaches.values())
        {
            if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
            {
                continue;
            }

            if (showZoneBorders)
            {
                if (renderLines)
                {
                    this.drawRenderObject(cache.outlines, cameraPos);
                }
                else
                {
                    this.drawRenderObject(cache.quads, cameraPos);
                }
            }

            if (renderLetters)
            {
                this.drawRenderObject(cache.letters, cameraPos);
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
        this.drawRenderObject(obj, cameraPos, this.glLineWidth);
    }

    private void drawRenderObject(PortalRenderObjectVbo obj, Vec3d cameraPos, float lineWidth)
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
            obj.lineWidthPublic(lineWidth);
        }

        obj.drawPostPublic(setLineWidth);
    }

    private boolean hasVisibleDirtyPortals(Vec3d cameraPos, MinecraftClient mc)
    {
        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        boolean showZoneBorders = settings.isShowZoneBorders();
        boolean renderLines = settings.shouldRenderLines();
        boolean renderLetters = settings.shouldRenderLetters();
        double maxRange = mc.options.getViewDistance().getValue() * 16.0D * 2.0D;
        double maxRangeSq = maxRange * maxRange;

        for (PortalRenderCache cache : this.portalRenderCaches.values())
        {
            if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
            {
                continue;
            }

            if (showZoneBorders)
            {
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

            // Check letters dirty state only if render letters is enabled
            if (renderLetters && (cache.lettersDirty || cache.letters.isUploadedPublic() == false))
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
        private PortalRenderObjectVbo letters;
        private boolean quadsDirty = true;
        private boolean outlinesDirty = true;
        private boolean lettersDirty = true;

        private PortalRenderCache(int portalIndex, int color, PortalInfluence influence)
        {
            this.portalIndex = portalIndex;
            this.color = color;
            this.influence = influence;
            this.quads = this.createQuads();
            this.outlines = this.createOutlines();
            this.letters = this.createLetters();
        }

        private PortalRenderObjectVbo createQuads()
        {
            return new PortalRenderObjectVbo(
                    () -> "minihud-portal:portal_zones/quads/" + this.portalIndex,
                    MaLiLibPipelines.POSITION_COLOR_MASA_LEQUAL_DEPTH_OFFSET_1);
        }

        private PortalRenderObjectVbo createOutlines()
        {
            return new PortalRenderObjectVbo(
                    () -> "minihud-portal:portal_zones/outlines/" + this.portalIndex,
                    MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH);
        }

        private PortalRenderObjectVbo createLetters()
        {
            return new PortalRenderObjectVbo(
                    () -> "minihud-portal:portal_zones/letters/" + this.portalIndex,
                    MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_NO_DEPTH_NO_CULL);  // No depth test - renders through walls
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
            if (this.quads.isUploadedPublic() || this.outlines.isUploadedPublic() || this.letters.isUploadedPublic())
            {
                this.resetBuffers();
            }
        }

        private void resetBuffers()
        {
            this.quads.closePublic();
            this.outlines.closePublic();
            this.letters.closePublic();
            this.quads = this.createQuads();
            this.outlines = this.createOutlines();
            this.letters = this.createLetters();
            this.quadsDirty = true;
            this.outlinesDirty = true;
            this.lettersDirty = true;
        }

        private void close()
        {
            this.quads.closePublic();
            this.outlines.closePublic();
            this.letters.closePublic();
        }
    }

    private record PortalCandidate(PortalBounds bounds, int color, String dimensionId)
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
