package ninja.trek.portal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import fi.dy.masa.malilib.util.position.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import fi.dy.masa.malilib.interfaces.IRangeChangeListener;
import fi.dy.masa.malilib.render.MaLiLibPipelines;
import fi.dy.masa.malilib.util.position.LayerRange;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.minihud.renderer.OverlayRendererBase;
import fi.dy.masa.minihud.renderer.RenderUtils;

public class PortalZoneRenderer extends OverlayRendererBase implements IRangeChangeListener
{
    public static final PortalZoneRenderer INSTANCE = new PortalZoneRenderer();
    private static final Logger LOGGER = LogManager.getLogger("minihud-portal");

    private static final int MAX_GROUPS_PER_TICK = 1;
    private static final short NO_PORTAL = -1;
    private static final float LETTER_STROKE_PIXELS = 2.5f;
    private static final float HIGHLIGHT_STROKE_PIXELS = 7.5f;
    private static final float LETTER_STROKE_RELATIVE_FALLBACK = 0.12f;
    private static final int HIGHLIGHT_COLOR = 0xFFFF55;

    private final List<PortalWorkGroup> pendingGroups = new ArrayList<>();
    private final Int2ObjectOpenHashMap<LongOpenHashSet> positionsByPortal = new Int2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<PortalRenderCache> portalRenderCaches = new Int2ObjectOpenHashMap<>();
    private final Map<UUID, LetterRenderCache> currentDimensionLetterCaches = new HashMap<>();
    private final LayerRange layerRange = new LayerRange(this);

    private int nextGroupIndex;
    private boolean needsFullRebuild = true;
    private boolean renderDirty = true;
    private boolean portalDataDirty = true;
    private boolean hasData;
    private boolean lastShowZoneBorders;
    private boolean lastRenderLines;
    private boolean lastRenderThrough;
    private boolean lastSimpleMode;
    private boolean loggedNoDataSinceToggle;
    private boolean loggedMissingTarget;
    private boolean pendingToggleDiagnostics;
    private float lastCameraYaw = Float.NaN;
    private float lastCameraPitch = Float.NaN;
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
    public boolean shouldRender(Minecraft mc)
    {
        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        boolean showZoneBorders = settings.isShowZoneBorders();
        boolean renderLetters = settings.shouldRenderLetters();
        boolean hasWorld = mc.level != null;
        PortalLinking.TargetDimension target = hasWorld ? this.resolveTarget(mc.level) : null;
        boolean shouldRender = (showZoneBorders || renderLetters) && hasWorld && target != null;

        if (this.pendingToggleDiagnostics)
        {
            LOGGER.info(
                    "Portal zone borders diagnostics: shouldRender={} showZoneBorders={} renderLetters={} hasWorld={} targetDimension={}",
                    shouldRender,
                    showZoneBorders,
                    renderLetters,
                    hasWorld,
                    target != null ? target.dimensionId() : "<none>");
        }

        return shouldRender;
    }

    @Override
    public boolean needsUpdate(Entity entity, Minecraft mc)
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

        if (mc.level == null)
        {
            return false;
        }

        if (this.shouldUpdateLettersForCamera(mc))
        {
            return true;
        }

        Vec3d entityPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
        return this.hasVisibleDirtyPortals(entityPos, mc);
    }

    @Override
    public void update(Vec3d cameraPos, Entity entity, Minecraft mc, ProfilerFiller profiler)
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

        if (showZoneBorders == false && renderLetters == false || mc.level == null || entity == null)
        {
            if (this.pendingToggleDiagnostics)
            {
                LOGGER.info("Portal zone borders diagnostics: render blocked (world={}, entity={})",
                        mc.level != null,
                        entity != null);
                this.pendingToggleDiagnostics = false;
            }
            if (showZoneBorders && this.loggedMissingTarget == false)
            {
                LOGGER.info("Portal zone borders render blocked (world={}, entity={})",
                        mc.level != null,
                        entity != null);
                this.loggedMissingTarget = true;
            }
            this.resetState();
            this.lastShowZoneBorders = showZoneBorders;
            this.lastRenderLines = settings.shouldRenderLines();
            this.lastRenderThrough = settings.shouldRenderThrough();
            return;
        }

        Level world = mc.level;
        PortalLinking.TargetDimension target = this.resolveTarget(world);

        if (target == null)
        {
            if (this.pendingToggleDiagnostics)
            {
                String dimensionId = world.dimension().identifier().toString();
                LOGGER.info("Portal zone borders diagnostics: render blocked (unsupported dimension={})", dimensionId);
                this.pendingToggleDiagnostics = false;
            }
            if (showZoneBorders && this.loggedMissingTarget == false)
            {
                String dimensionId = world.dimension().identifier().toString();
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

        if (settings.isSimpleMode() != this.lastSimpleMode)
        {
            this.needsFullRebuild = true;
        }

        String dimensionId = world.dimension().identifier().toString();

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

        if (this.hasData() || (renderLetters && this.hasLetterPortals()))
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
        this.lastSimpleMode = settings.isSimpleMode();
        this.updateCameraAngles(mc);
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
        this.clearCurrentDimensionLetterCaches();
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

    private void rebuild(Level world, PortalLinking.TargetDimension target)
    {
        this.clearPositions();
        this.searchContext = this.buildSearchContext(world, target);
        this.lastDimensionId = world.dimension().identifier().toString();
        this.syncCurrentDimensionLetterCaches(world);

        if (this.searchContext == null || this.searchContext.portals.isEmpty())
        {
            return;
        }

        this.initializePortalRenderCaches();
        this.pendingGroups.clear();
        this.pendingGroups.addAll(this.buildWorkGroups(this.searchContext.influences));
        this.nextGroupIndex = 0;
    }

    private void processGroups(PortalLinking.TargetDimension target)
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

    private void syncCurrentDimensionLetterCaches(Level world)
    {
        this.clearCurrentDimensionLetterCaches();
        String dimensionId = world.dimension().identifier().toString();

        for (PortalEntry entry : PortalDataStore.getInstance().getPortals())
        {
            if (entry.getDimensionId().equals(dimensionId))
            {
                this.currentDimensionLetterCaches.put(entry.getId(), new LetterRenderCache(entry));
            }
        }
    }

    private void clearCurrentDimensionLetterCaches()
    {
        for (LetterRenderCache cache : this.currentDimensionLetterCaches.values())
        {
            cache.close();
        }

        this.currentDimensionLetterCaches.clear();
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

    private void processGroup(PortalWorkGroup group, PortalLinking.TargetDimension target, PortalSearchContext context)
    {
        boolean simpleMode = PortalDataStore.getInstance().getZoneSettings().isSimpleMode();

        if (group.portalIndices.length == 1 || simpleMode)
        {
            // In simple mode, process each portal as isolated (show full influence without overlap calculations)
            for (int portalIndex : group.portalIndices)
            {
                PortalCandidate portal = context.portals.get(portalIndex);
                PortalInfluence influence = context.influences.get(portalIndex);
                this.processIsolatedPortal(portalIndex, portal, influence, target, context);
                this.markPortalDirty(portalIndex, true);
            }
            return;
        }

        this.processOverlapGroup(group, target, context);
        for (int portalIndex : group.portalIndices)
        {
            this.markPortalDirty(portalIndex, true);
        }
    }

    private void processIsolatedPortal(int portalIndex, PortalCandidate portal, PortalInfluence influence,
                                       PortalLinking.TargetDimension target, PortalSearchContext context)
    {
        int minY = influence.minY();
        int maxY = influence.maxY();
        int minX = influence.minX();
        int maxX = influence.maxX();
        int minZ = influence.minZ();
        int maxZ = influence.maxZ();

        // 1. Horizontal faces (Top/Bottom) - Full Coverage
        for (int x = minX; x <= maxX; ++x)
        {
            for (int z = minZ; z <= maxZ; ++z)
            {
                this.addPortalPosition(this.positionsByPortal, portalIndex, BlockPos.asLong(x, minY, z));
                if (maxY > minY)
                {
                    this.addPortalPosition(this.positionsByPortal, portalIndex, BlockPos.asLong(x, maxY, z));
                }
            }
        }

        // 2. Vertical faces (Sides) - Between Top/Bottom
        if (maxY > minY + 1)
        {
            // North/South (along X) - Full width
            for (int y = minY + 1; y < maxY; ++y)
            {
                for (int x = minX; x <= maxX; ++x)
                {
                    this.addPortalPosition(this.positionsByPortal, portalIndex, BlockPos.asLong(x, y, minZ));
                    if (maxZ > minZ)
                    {
                        this.addPortalPosition(this.positionsByPortal, portalIndex, BlockPos.asLong(x, y, maxZ));
                    }
                }
            }

            // East/West (along Z) - Between North/South
            if (maxZ > minZ + 1)
            {
                for (int y = minY + 1; y < maxY; ++y)
                {
                    for (int z = minZ + 1; z < maxZ; ++z)
                    {
                        this.addPortalPosition(this.positionsByPortal, portalIndex, BlockPos.asLong(minX, y, z));
                        if (maxX > minX)
                        {
                            this.addPortalPosition(this.positionsByPortal, portalIndex, BlockPos.asLong(maxX, y, z));
                        }
                    }
                }
            }
        }
    }

    private void processOverlapGroup(PortalWorkGroup group, PortalLinking.TargetDimension target, PortalSearchContext context)
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

                    // Check if this position is on the boundary of its zone.
                    // A position is a boundary if it belongs to zone and has at least one
                    // neighbor that is not part of zone (could be a different zone or no zone).
                    // This ensures each zone gets a complete perimeter border.
                    if (this.isBoundaryInGroup(x, y, z, zone, target, context, group.portalIndices))
                    {
                        this.addPortalPosition(this.positionsByPortal, zone, BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
    }

    private boolean isBoundaryInGroup(int worldX, int worldY, int worldZ, short currentZone,
                                      PortalLinking.TargetDimension target, PortalSearchContext context,
                                      int[] portalIndices)
    {
        // A position is on the boundary of currentZone if it has at least one neighbor
        // that is NOT part of currentZone (could be another zone or no zone)
        // This creates a complete perimeter border around each zone
        return this.resolvePortalIndex(worldX + 1, worldY, worldZ, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX - 1, worldY, worldZ, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX, worldY + 1, worldZ, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX, worldY - 1, worldZ, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX, worldY, worldZ + 1, target, context, portalIndices) != currentZone ||
               this.resolvePortalIndex(worldX, worldY, worldZ - 1, target, context, portalIndices) != currentZone;
    }

    private short resolvePortalIndex(int worldX, int worldY, int worldZ,
                                     PortalLinking.TargetDimension target,
                                     PortalSearchContext context, int[] portalIndices)
    {
        if (worldY < context.world.getMinY() || worldY > context.world.getMaxY())
        {
            return NO_PORTAL;
        }

        int portalIndex = PortalLinking.findClosestPortalIndex(
                worldX + 0.5D, worldY + 0.5D, worldZ + 0.5D,
                target, context.border, context.portalBounds, portalIndices);
        return portalIndex == PortalLinking.NO_PORTAL ? NO_PORTAL : (short) portalIndex;
    }

    private PortalSearchContext buildSearchContext(Level world, PortalLinking.TargetDimension target)
    {
        PortalSearchContext context = new PortalSearchContext(world);
        context.border = world.getWorldBorder();

        List<PortalCandidate> portals = new ArrayList<>();
        List<PortalBounds> portalBounds = new ArrayList<>();

        for (PortalEntry entry : PortalDataStore.getInstance().getPortals())
        {
            if (entry.getDimensionId().equals(target.dimensionId()) == false)
            {
                continue;
            }

            PortalBounds bounds = entry.getBounds();

            portals.add(new PortalCandidate(bounds, entry.getColor(), entry.getDimensionId()));
            portalBounds.add(bounds);
        }

        context.portals = portals;
        context.portalBounds = portalBounds;
        context.influences = this.buildInfluences(context, target);
        return context;
    }

    private List<PortalInfluence> buildInfluences(PortalSearchContext context,
                                                  PortalLinking.TargetDimension target)
    {
        if (context.portals.isEmpty())
        {
            return List.of();
        }

        int worldMinY = context.world.getMinY();
        int worldMaxY = context.world.getMaxY();
        List<PortalInfluence> influences = new ArrayList<>(context.portals.size());

        for (PortalCandidate portal : context.portals)
        {
            PortalBounds bounds = portal.bounds();
            double minDestX = bounds.getMinX() - target.searchRadius();
            double maxDestX = bounds.getMaxX() + target.searchRadius();
            double minDestZ = bounds.getMinZ() - target.searchRadius();
            double maxDestZ = bounds.getMaxZ() + target.searchRadius();
            int minSourceX = this.toSourceMin(minDestX, target.scale());
            int maxSourceX = this.toSourceMax(maxDestX, target.scale());
            int minSourceZ = this.toSourceMin(minDestZ, target.scale());
            int maxSourceZ = this.toSourceMax(maxDestZ, target.scale());

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

    private void renderPortals(Vec3d cameraPos, Minecraft mc, ProfilerFiller profiler, boolean renderLines)
    {
        if (mc.level == null || mc.player == null)
        {
            return;
        }

        Level world = mc.level;
        PortalLinking.TargetDimension target = this.resolveTarget(world);
        if (target == null || this.searchContext == null)
        {
            return;
        }

        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        boolean showZoneBorders = settings.isShowZoneBorders();
        boolean renderLetters = settings.shouldRenderLetters();
        int highlightedPortalIndex = PortalLinking.findClosestPortalIndex(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                target, this.searchContext.border, this.searchContext.portalBounds);

        double maxRange = mc.options.renderDistance().get() * 16.0D * 2.0D;
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
                    this.buildPortalLetters(cache, cameraPos, portal, target.scale(),
                                            cache.portalIndex == highlightedPortalIndex);
                }
            }
        }
        if (renderLetters)
        {
            this.buildCurrentDimensionLetters(cameraPos, maxRangeSq);
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
                this.renderThrough ? MaLiLibPipelines.POSITION_COLOR_MASA_NO_DEPTH_NO_CULL : MaLiLibPipelines.POSITION_COLOR_MASA_LEQUAL_DEPTH_OFFSET_1,
                0);
        Color4f color = Color4f.fromColor(cache.color, 0.3f);
        RenderUtils.renderBlockPositions(positions, this.layerRange, color, 0.0D, cameraPos, builder);

        try
        {
            MeshData meshData = builder.build();

            if (meshData != null)
            {
                cache.quads.upload(meshData, this.shouldResort);

                if (this.shouldResort)
                {
                    cache.quads.startResorting(meshData, cache.quads.createVertexSorterPublic(cameraPos.toVanilla()));
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
                MaLiLibPipelines.DEBUG_LINES_MASA_SIMPLE_LEQUAL_DEPTH,
                0);
        Color4f color = Color4f.fromColor(cache.color, 1.0f);
        RenderUtils.renderBlockPositionOutlines(positions, this.layerRange, color, 0.0D, cameraPos, 1.0f, builder);

        try
        {
            MeshData meshData = builder.build();

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

    private void buildPortalLetters(PortalRenderCache cache, Vec3d cameraPos, PortalCandidate portal,
                                    double scale, boolean highlighted)
    {
        if (cache.lettersDirty == false && cache.letters.isUploadedPublic())
        {
            return;
        }

        this.buildPortalLetters(cache.letters, cameraPos, portal.bounds(), cache.color, portal.dimensionId(),
                                scale, Integer.toString(cache.portalIndex), highlighted);

        cache.lettersDirty = false;
    }

    private void buildPortalLetters(PortalRenderObjectVbo letters, Vec3d cameraPos, PortalBounds bounds, int color,
                                    String portalDimensionId, double scale, String cacheKey, boolean highlighted)
    {
        BufferBuilder builder = letters.start(
                () -> "minihud-portal:portal_zones/letters/" + cacheKey,
                MaLiLibPipelines.POSITION_COLOR_MASA_NO_DEPTH_NO_CULL,
                0);  // No depth test - renders through walls

        // Calculate portal center (portal bounds are in portal dimension coordinates)
        double centerX = (bounds.getMinX() + bounds.getMaxX() + 1.0) / 2.0;
        double centerY = (bounds.getMinY() + bounds.getMaxY() + 1.0) / 2.0;
        double centerZ = (bounds.getMinZ() + bounds.getMaxZ() + 1.0) / 2.0;

        // Translate to source dimension position (inverse of the scale)
        double translatedCenterX = centerX / scale;
        double translatedCenterZ = centerZ / scale;

        // Calculate letter size based on portal's actual size (scaled to source X/Z)
        double portalWidthX = bounds.getMaxX() - bounds.getMinX() + 1.0;
        double portalWidthZ = bounds.getMaxZ() - bounds.getMinZ() + 1.0;
        double portalWidth = Math.max(portalWidthX, portalWidthZ);
        double portalHeight = bounds.getMaxY() - bounds.getMinY() + 1.0;
        double letterWidth = portalWidth / Math.abs(scale);
        double letterHeight = portalHeight;

        // Determine letter based on portal's own dimension
        boolean isNether = portalDimensionId.equals(Level.NETHER.identifier().toString());

        char letterChar = isNether ? 'N' : 'O';
        LOGGER.info("Building letter '{}' for portal {} at ({}, {}, {}), size={}x{}, portalDim={}",
                   letterChar, cacheKey, translatedCenterX, centerY, translatedCenterZ,
                   letterWidth, letterHeight, portalDimensionId);

        Vec3 viewDir = this.getCameraViewDirection();
        Color4f letterColor = Color4f.fromColor(highlighted ? HIGHLIGHT_COLOR : color, 1.0f);
        float strokePixels = highlighted ? HIGHLIGHT_STROKE_PIXELS : LETTER_STROKE_PIXELS;
        this.drawBillboardedLetter(builder, translatedCenterX, centerY, translatedCenterZ,
                                   letterWidth, letterHeight, letterChar, letterColor,
                                   cameraPos, viewDir, strokePixels);

        try
        {
            MeshData meshData = builder.build();

            if (meshData != null)
            {
                letters.upload(meshData, false);
                meshData.close();
                LOGGER.info("Uploaded letter mesh for portal {}", cacheKey);
            }
            else
            {
                LOGGER.warn("Failed to build letter mesh for portal {} - meshData is null", cacheKey);
            }
        }
        catch (Exception e)
        {
            LOGGER.error("Error building letter mesh for portal {}", cacheKey, e);
        }
    }

    private void drawBillboardedLetter(BufferBuilder builder, double worldX, double worldY, double worldZ,
                                       double width, double height, char letter, Color4f color,
                                       Vec3d cameraPos, Vec3 viewDir, float strokePixels)
    {
        // Camera-relative position
        float cx = (float) (worldX - cameraPos.x);
        float cy = (float) (worldY - cameraPos.y);
        float cz = (float) (worldZ - cameraPos.z);
        float halfWidth = (float) (width / 2.0);
        float halfHeight = (float) (height / 2.0);
        float stroke = this.computeLetterStroke(
                worldX, worldY, worldZ, halfWidth, halfHeight, cameraPos, strokePixels);

        // For proper billboarding, we need to construct a coordinate system
        // where the letter faces the camera.
        // View direction: from letter to camera (opposite of camera look direction)
        float viewX = (float) -viewDir.x;
        float viewY = (float) -viewDir.y;
        float viewZ = (float) -viewDir.z;
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
            // Left vertical bar
            this.addAxisAlignedQuad(builder, cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ,
                                    -halfWidth, -halfHeight, -halfWidth + stroke, halfHeight, color);
            // Right vertical bar
            this.addAxisAlignedQuad(builder, cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ,
                                    halfWidth - stroke, -halfHeight, halfWidth, halfHeight, color);
            // Diagonal bar
            this.addStrokeQuad(builder, cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ,
                               -halfWidth + stroke, halfHeight, halfWidth - stroke, -halfHeight, stroke, color);
        }
        else if (letter == 'O')
        {
            // Draw O letter as a multi-segment ring
            final int segments = 16;
            final float step = (float) ((Math.PI * 2.0) / segments);
            for (int i = 0; i < segments; i++)
            {
                float angle1 = i * step;
                float angle2 = (i + 1) * step;

                float cos1 = (float) Math.cos(angle1);
                float sin1 = (float) Math.sin(angle1);
                float cos2 = (float) Math.cos(angle2);
                float sin2 = (float) Math.sin(angle2);

                float outerX1 = cos1 * halfWidth;
                float outerY1 = sin1 * halfHeight;
                float outerX2 = cos2 * halfWidth;
                float outerY2 = sin2 * halfHeight;
                float innerX1 = cos1 * (halfWidth - stroke);
                float innerY1 = sin1 * (halfHeight - stroke);
                float innerX2 = cos2 * (halfWidth - stroke);
                float innerY2 = sin2 * (halfHeight - stroke);

                this.addQuad(builder, cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ,
                             outerX1, outerY1, outerX2, outerY2, innerX2, innerY2, innerX1, innerY1, color);
            }
        }
    }

    private void addAxisAlignedQuad(BufferBuilder builder, float cx, float cy, float cz,
                                    float rightX, float rightY, float rightZ,
                                    float upX, float upY, float upZ,
                                    float x1, float y1, float x2, float y2, Color4f color)
    {
        this.addQuad(builder, cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ,
                     x1, y1, x2, y1, x2, y2, x1, y2, color);
    }

    private void addStrokeQuad(BufferBuilder builder, float cx, float cy, float cz,
                               float rightX, float rightY, float rightZ,
                               float upX, float upY, float upZ,
                               float x1, float y1, float x2, float y2,
                               float stroke, Color4f color)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.0001f)
        {
            return;
        }

        float nx = -dy / length;
        float ny = dx / length;
        float halfStroke = stroke * 0.5f;
        float ox = nx * halfStroke;
        float oy = ny * halfStroke;

        this.addQuad(builder, cx, cy, cz, rightX, rightY, rightZ, upX, upY, upZ,
                     x1 + ox, y1 + oy, x2 + ox, y2 + oy, x2 - ox, y2 - oy, x1 - ox, y1 - oy, color);
    }

    private void addQuad(BufferBuilder builder, float cx, float cy, float cz,
                         float rightX, float rightY, float rightZ,
                         float upX, float upY, float upZ,
                         float x1, float y1, float x2, float y2,
                         float x3, float y3, float x4, float y4, Color4f color)
    {
        builder.addVertex(cx + rightX * x1 + upX * y1, cy + rightY * x1 + upY * y1, cz + rightZ * x1 + upZ * y1)
               .setColor(color.r, color.g, color.b, color.a);
        builder.addVertex(cx + rightX * x2 + upX * y2, cy + rightY * x2 + upY * y2, cz + rightZ * x2 + upZ * y2)
               .setColor(color.r, color.g, color.b, color.a);
        builder.addVertex(cx + rightX * x3 + upX * y3, cy + rightY * x3 + upY * y3, cz + rightZ * x3 + upZ * y3)
               .setColor(color.r, color.g, color.b, color.a);
        builder.addVertex(cx + rightX * x4 + upX * y4, cy + rightY * x4 + upY * y4, cz + rightZ * x4 + upZ * y4)
               .setColor(color.r, color.g, color.b, color.a);
    }
    private Vec3 getCameraViewDirection()
    {
        Minecraft mc = Minecraft.getInstance();

        if (mc.gameRenderer != null && mc.gameRenderer.mainCamera() != null)
        {
            float pitch = mc.gameRenderer.mainCamera().xRot();
            float yaw = mc.gameRenderer.mainCamera().yRot();
            return Vec3.directionFromRotation(pitch, yaw);
        }

        return new Vec3(0.0, 0.0, 1.0);
    }

    private float computeLetterStroke(double worldX, double worldY, double worldZ,
                                      float halfWidth, float halfHeight, Vec3d cameraPos,
                                      float strokePixels)
    {
        if (halfWidth <= 0.0f || halfHeight <= 0.0f)
        {
            return 0.0f;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.gameRenderer.mainCamera() == null || mc.getWindow() == null)
        {
            return Math.min(halfWidth, halfHeight) * LETTER_STROKE_RELATIVE_FALLBACK;
        }

        int framebufferHeight = mc.getWindow().getHeight();
        if (framebufferHeight <= 0)
        {
            return Math.min(halfWidth, halfHeight) * LETTER_STROKE_RELATIVE_FALLBACK;
        }

        double dx = worldX - cameraPos.x;
        double dy = worldY - cameraPos.y;
        double dz = worldZ - cameraPos.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 0.01)
        {
            distance = 0.01;
        }

        double fovDegrees = mc.options.fov().get();
        double fovRadians = Math.toRadians(fovDegrees);
        double worldPerPixel = 2.0 * distance * Math.tan(fovRadians / 2.0) / framebufferHeight;
        float stroke = (float) (strokePixels * worldPerPixel);
        return Math.min(stroke, Math.min(halfWidth, halfHeight));
    }

    private boolean shouldUpdateLettersForCamera(Minecraft mc)
    {
        if (PortalDataStore.getInstance().getZoneSettings().shouldRenderLetters() == false)
        {
            return false;
        }

        if (mc.gameRenderer == null || mc.gameRenderer.mainCamera() == null)
        {
            return false;
        }

        float yaw = mc.gameRenderer.mainCamera().yRot();
        float pitch = mc.gameRenderer.mainCamera().xRot();

        if (Float.isNaN(this.lastCameraYaw) || Float.isNaN(this.lastCameraPitch) ||
            Math.abs(yaw - this.lastCameraYaw) > 0.001f || Math.abs(pitch - this.lastCameraPitch) > 0.001f)
        {
            this.markAllLettersDirty();
            return true;
        }

        return false;
    }

    private void updateCameraAngles(Minecraft mc)
    {
        if (mc.gameRenderer == null || mc.gameRenderer.mainCamera() == null)
        {
            return;
        }

        this.lastCameraYaw = mc.gameRenderer.mainCamera().yRot();
        this.lastCameraPitch = mc.gameRenderer.mainCamera().xRot();
    }

    private void markAllLettersDirty()
    {
        for (PortalRenderCache cache : this.portalRenderCaches.values())
        {
            cache.lettersDirty = true;
        }

        for (LetterRenderCache cache : this.currentDimensionLetterCaches.values())
        {
            cache.lettersDirty = true;
        }
    }

    @Override
    public void render(Vec3d cameraPos, Minecraft mc, ProfilerFiller profiler)
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
            for (LetterRenderCache cache : this.currentDimensionLetterCaches.values())
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
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null)
        {
            return;
        }

        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        boolean showZoneBorders = settings.isShowZoneBorders();
        boolean renderLines = settings.shouldRenderLines();
        boolean renderLetters = settings.shouldRenderLetters();
        double maxRange = mc.options.renderDistance().get() * 16.0D * 2.0D;
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

        if (renderLetters)
        {
            for (LetterRenderCache cache : this.currentDimensionLetterCaches.values())
            {
                if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
                {
                    continue;
                }

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
            obj.resortTranslucentPublic(obj.createVertexSorterPublic(cameraPos.toVanilla()));
        }

        obj.drawPostPublic(false);
    }

    private boolean hasVisibleDirtyPortals(Vec3d cameraPos, Minecraft mc)
    {
        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();
        boolean showZoneBorders = settings.isShowZoneBorders();
        boolean renderLines = settings.shouldRenderLines();
        boolean renderLetters = settings.shouldRenderLetters();
        double maxRange = mc.options.renderDistance().get() * 16.0D * 2.0D;
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

        if (renderLetters)
        {
            for (LetterRenderCache cache : this.currentDimensionLetterCaches.values())
            {
                if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
                {
                    continue;
                }

                if (cache.lettersDirty || cache.letters.isUploadedPublic() == false)
                {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean hasLetterPortals()
    {
        if (this.searchContext != null && this.searchContext.portals.isEmpty() == false)
        {
            return true;
        }

        return this.currentDimensionLetterCaches.isEmpty() == false;
    }

    private void buildCurrentDimensionLetters(Vec3d cameraPos, double maxRangeSq)
    {
        for (LetterRenderCache cache : this.currentDimensionLetterCaches.values())
        {
            if (cache == null || cache.isInRange(cameraPos, maxRangeSq) == false)
            {
                continue;
            }

            if (cache.lettersDirty == false && cache.letters.isUploadedPublic())
            {
                continue;
            }

            PortalEntry portal = cache.portal;
            this.buildPortalLetters(cache.letters, cameraPos, portal.getBounds(), cache.color,
                                    portal.getDimensionId(), 1.0D, cache.key, false);
            cache.lettersDirty = false;
        }
    }

    @Nullable
    private PortalLinking.TargetDimension resolveTarget(Level world)
    {
        return PortalLinking.resolveTarget(world.dimension().identifier().toString());
    }

    private static class PortalSearchContext
    {
        private final Level world;
        private WorldBorder border;
        private List<PortalCandidate> portals = List.of();
        private List<PortalBounds> portalBounds = List.of();
        private List<PortalInfluence> influences = List.of();

        private PortalSearchContext(Level world)
        {
            this.world = world;
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
                    MaLiLibPipelines.POSITION_COLOR_MASA_NO_DEPTH_NO_CULL);  // No depth test - renders through walls
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

    private static class LetterRenderCache
    {
        private final PortalEntry portal;
        private final PortalRenderObjectVbo letters;
        private final int color;
        private final String key;
        private boolean lettersDirty = true;

        private LetterRenderCache(PortalEntry portal)
        {
            this.portal = portal;
            this.color = portal.getColor();
            this.key = "current/" + portal.getId().toString();
            this.letters = new PortalRenderObjectVbo(
                    () -> "minihud-portal:portal_zones/letters/" + this.key,
                    MaLiLibPipelines.POSITION_COLOR_MASA_NO_DEPTH_NO_CULL);
        }

        private boolean isInRange(Vec3d cameraPos, double maxRangeSq)
        {
            PortalBounds bounds = this.portal.getBounds();
            double centerX = (bounds.getMinX() + bounds.getMaxX()) / 2.0;
            double centerZ = (bounds.getMinZ() + bounds.getMaxZ()) / 2.0;
            double dx = cameraPos.x - centerX;
            double dz = cameraPos.z - centerZ;
            return (dx * dx + dz * dz) <= maxRangeSq;
        }

        private void close()
        {
            this.letters.closePublic();
        }
    }

    private record PortalCandidate(PortalBounds bounds, int color, String dimensionId)
    {
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
}
