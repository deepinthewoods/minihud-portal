package ninja.trek.portal;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public final class PortalLinkPreview
{
    private PortalLinkPreview()
    {
    }

    public record Preview(PortalBounds portalBounds, LongOpenHashSet frameBlocks, List<PortalEntry> linkedPortals)
    {
    }

    public record PlacementPreview(PortalBounds portalBounds, LongOpenHashSet frameBlocks)
    {
    }

    public static @Nullable Preview compute(Minecraft mc)
    {
        if (mc == null || mc.level == null || mc.player == null)
        {
            return null;
        }

        Level world = mc.level;
        String currentDimensionId = world.dimension().identifier().toString();
        LinkTarget linkTarget = resolveLinkTarget(currentDimensionId);

        if (linkTarget == null)
        {
            return null;
        }

        PlacementPreview placementPreview = computePlacementPreview(mc);

        if (placementPreview == null)
        {
            return null;
        }

        List<PortalEntry> linked = findLinkedPortals(world, currentDimensionId, linkTarget, placementPreview.portalBounds);
        return new Preview(placementPreview.portalBounds, placementPreview.frameBlocks, linked);
    }

    public static @Nullable PlacementPreview computePlacementPreview(Minecraft mc)
    {
        if (mc == null || mc.level == null || mc.player == null)
        {
            return null;
        }

        String currentDimensionId = mc.level.dimension().identifier().toString();

        if (resolveLinkTarget(currentDimensionId) == null)
        {
            return null;
        }

        Placement placement = computePlacement(mc.player);

        if (placement == null)
        {
            return null;
        }

        return new PlacementPreview(placement.portalBounds, placement.frameBlocks);
    }

    private static @Nullable LinkTarget resolveLinkTarget(String currentDimensionId)
    {
        String overworldId = Level.OVERWORLD.identifier().toString();
        String netherId = Level.NETHER.identifier().toString();

        if (currentDimensionId.equals(overworldId))
        {
            return new LinkTarget(netherId, 8.0D, 128);
        }

        if (currentDimensionId.equals(netherId))
        {
            return new LinkTarget(overworldId, 1.0D / 8.0D, 16);
        }

        return null;
    }

    private static List<PortalEntry> findLinkedPortals(Level world, String currentDimensionId,
                                                       LinkTarget linkTarget, PortalBounds destinationPortal)
    {
        List<PortalEntry> allPortals = PortalDataStore.getInstance().getPortals();
        List<PortalBounds> targetCandidates = new ArrayList<>();
        targetCandidates.add(destinationPortal);

        for (PortalEntry entry : allPortals)
        {
            if (entry.getDimensionId().equals(currentDimensionId))
            {
                targetCandidates.add(entry.getBounds());
            }
        }

        WorldBorder border = world.getWorldBorder();
        BorderInfo borderInfo = new BorderInfo(border);
        List<PortalEntry> linked = new ArrayList<>();

        for (PortalEntry entry : allPortals)
        {
            if (entry.getDimensionId().equals(linkTarget.sourceDimensionId) == false)
            {
                continue;
            }

            if (linksToDestination(entry.getBounds(), targetCandidates, linkTarget, borderInfo))
            {
                linked.add(entry);
            }
        }

        return linked;
    }

    private static boolean linksToDestination(PortalBounds sourceBounds, List<PortalBounds> targetCandidates,
                                              LinkTarget linkTarget, BorderInfo borderInfo)
    {
        for (int y = sourceBounds.getMinY(); y <= sourceBounds.getMaxY(); ++y)
        {
            for (int z = sourceBounds.getMinZ(); z <= sourceBounds.getMaxZ(); ++z)
            {
                for (int x = sourceBounds.getMinX(); x <= sourceBounds.getMaxX(); ++x)
                {
                    int bestIndex = resolvePortalIndex(x, y, z, linkTarget, borderInfo, targetCandidates);

                    if (bestIndex == 0)
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static int resolvePortalIndex(int worldX, int worldY, int worldZ, LinkTarget linkTarget,
                                          BorderInfo borderInfo, List<PortalBounds> targetCandidates)
    {
        int destX = borderInfo.clampX((worldX + 0.5D) * linkTarget.scale);
        int destZ = borderInfo.clampZ((worldZ + 0.5D) * linkTarget.scale);
        int destY = Mth.floor(worldY + 0.5D);
        int radius = linkTarget.searchRadius;

        int bestIndex = -1;
        double bestDist = Double.POSITIVE_INFINITY;

        for (int candidateIndex = 0; candidateIndex < targetCandidates.size(); ++candidateIndex)
        {
            PortalBounds bounds = targetCandidates.get(candidateIndex);

            if (isOutsideSearchSquare(bounds, destX, destZ, radius))
            {
                continue;
            }

            int portalMinY = bounds.getMinY();

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
                        bestIndex = candidateIndex;
                    }
                }
            }
        }

        return bestIndex;
    }

    private static boolean isOutsideSearchSquare(PortalBounds bounds, int destX, int destZ, int radius)
    {
        return bounds.getMaxX() < destX - radius || bounds.getMinX() > destX + radius ||
               bounds.getMaxZ() < destZ - radius || bounds.getMinZ() > destZ + radius;
    }

    private static Placement computePlacement(Player player)
    {
        BlockPos basePos = BlockPos.containing(player.getX(), player.getY(), player.getZ());
        Direction facing = player.getDirection();
        Direction left = facing.getCounterClockWise();
        Direction right = facing.getClockWise();

        double centerX = basePos.getX() + 0.5D;
        double centerZ = basePos.getZ() + 0.5D;
        double offsetX = player.getX() - centerX;
        double offsetZ = player.getZ() - centerZ;
        double leftDot = offsetX * left.getStepX() + offsetZ * left.getStepZ();
        boolean leftSide = leftDot >= 0.0D;

        BlockPos otherColumn = leftSide ? basePos.relative(left) : basePos.relative(right);
        int minY = basePos.getY();
        int maxY = minY + 2;
        int minX;
        int maxX;
        int minZ;
        int maxZ;

        if (facing.getAxis() == Direction.Axis.Z)
        {
            minX = Math.min(basePos.getX(), otherColumn.getX());
            maxX = Math.max(basePos.getX(), otherColumn.getX());
            minZ = basePos.getZ();
            maxZ = basePos.getZ();
        }
        else
        {
            minX = basePos.getX();
            maxX = basePos.getX();
            minZ = Math.min(basePos.getZ(), otherColumn.getZ());
            maxZ = Math.max(basePos.getZ(), otherColumn.getZ());
        }

        PortalBounds portalBounds = new PortalBounds(minX, minY, minZ, maxX, maxY, maxZ);
        LongOpenHashSet frameBlocks = buildFrameBlocks(portalBounds, facing, basePos);
        return new Placement(portalBounds, frameBlocks);
    }

    private static LongOpenHashSet buildFrameBlocks(PortalBounds portalBounds, Direction facing, BlockPos basePos)
    {
        LongOpenHashSet frameBlocks = new LongOpenHashSet();
        int frameMinY = portalBounds.getMinY() - 1;
        int frameMaxY = portalBounds.getMaxY() + 1;

        if (facing.getAxis() == Direction.Axis.Z)
        {
            int frameMinX = portalBounds.getMinX() - 1;
            int frameMaxX = portalBounds.getMaxX() + 1;
            int z = basePos.getZ();

            for (int y = frameMinY; y <= frameMaxY; ++y)
            {
                for (int x = frameMinX; x <= frameMaxX; ++x)
                {
                    if (x == frameMinX || x == frameMaxX || y == frameMinY || y == frameMaxY)
                    {
                        frameBlocks.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
        else
        {
            int frameMinZ = portalBounds.getMinZ() - 1;
            int frameMaxZ = portalBounds.getMaxZ() + 1;
            int x = basePos.getX();

            for (int y = frameMinY; y <= frameMaxY; ++y)
            {
                for (int z = frameMinZ; z <= frameMaxZ; ++z)
                {
                    if (z == frameMinZ || z == frameMaxZ || y == frameMinY || y == frameMaxY)
                    {
                        frameBlocks.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }

        return frameBlocks;
    }

    private record BorderInfo(double west, double east, double north, double south)
    {
        private BorderInfo(WorldBorder border)
        {
            this(border.getMinX(), border.getMaxX() - 1.0E-5D,
                 border.getMinZ(), border.getMaxZ() - 1.0E-5D);
        }

        private int clampX(double x)
        {
            return Mth.floor(Mth.clamp(x, this.west, this.east));
        }

        private int clampZ(double z)
        {
            return Mth.floor(Mth.clamp(z, this.north, this.south));
        }
    }

    private record LinkTarget(String sourceDimensionId, double scale, int searchRadius)
    {
    }

    private record Placement(PortalBounds portalBounds, LongOpenHashSet frameBlocks)
    {
    }
}
