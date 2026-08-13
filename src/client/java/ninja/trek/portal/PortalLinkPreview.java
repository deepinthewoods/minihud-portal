package ninja.trek.portal;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public final class PortalLinkPreview
{
    private PortalLinkPreview()
    {
    }

    public record Preview(PortalBounds portalBounds, LongOpenHashSet frameBlocks,
                          @Nullable PortalEntry destinationPortal)
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

        PlacementPreview placementPreview = computePlacementPreview(mc);

        if (placementPreview == null)
        {
            return null;
        }

        PortalEntry destination = findDestinationPortal(mc);
        return new Preview(placementPreview.portalBounds, placementPreview.frameBlocks, destination);
    }

    /**
     * Returns the existing portal Minecraft would choose for an entity entering
     * the hypothetical portal at the player's exact current position.
     */
    public static @Nullable PortalEntry findDestinationPortal(Minecraft mc)
    {
        if (mc == null || mc.level == null || mc.player == null)
        {
            return null;
        }

        Level world = mc.level;
        String currentDimensionId = world.dimension().identifier().toString();
        PortalLinking.TargetDimension target = PortalLinking.resolveTarget(currentDimensionId);

        if (target == null)
        {
            return null;
        }

        List<PortalEntry> candidates = new ArrayList<>();
        List<PortalBounds> candidateBounds = new ArrayList<>();

        for (PortalEntry entry : PortalDataStore.getInstance().getPortals())
        {
            if (entry.getDimensionId().equals(target.dimensionId()))
            {
                candidates.add(entry);
                candidateBounds.add(entry.getBounds());
            }
        }

        int portalIndex = PortalLinking.findClosestPortalIndex(
                mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                target, world.getWorldBorder(), candidateBounds);

        return portalIndex != PortalLinking.NO_PORTAL ? candidates.get(portalIndex) : null;
    }

    public static @Nullable PlacementPreview computePlacementPreview(Minecraft mc)
    {
        if (mc == null || mc.level == null || mc.player == null)
        {
            return null;
        }

        String currentDimensionId = mc.level.dimension().identifier().toString();

        if (PortalLinking.resolveTarget(currentDimensionId) == null)
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

    private record Placement(PortalBounds portalBounds, LongOpenHashSet frameBlocks)
    {
    }
}
