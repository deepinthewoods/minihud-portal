package ninja.trek.portal;

import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side model of the existing-portal selection in PortalForcer.
 *
 * <p>The server searches portal POIs in an inclusive X/Z square, chooses the
 * portal block with the smallest squared 3D distance, and uses the lower Y as
 * its explicit tie-breaker. Keeping that rule here prevents the zone renderer
 * and the at-player preview from drifting apart.</p>
 */
final class PortalLinking
{
    static final int NO_PORTAL = -1;
    private static final double WORLD_BORDER_EPSILON = (double) 1.0E-5F;

    private PortalLinking()
    {
    }

    static @Nullable TargetDimension resolveTarget(String sourceDimensionId)
    {
        if (sourceDimensionId.equals(Level.NETHER.identifier().toString()))
        {
            return new TargetDimension(Level.OVERWORLD.identifier().toString(), 8.0D, 128);
        }

        if (sourceDimensionId.equals(Level.OVERWORLD.identifier().toString()))
        {
            return new TargetDimension(Level.NETHER.identifier().toString(), 1.0D / 8.0D, 16);
        }

        return null;
    }

    static int findClosestPortalIndex(double sourceX, double sourceY, double sourceZ,
                                      TargetDimension target, WorldBorder destinationBorder,
                                      List<PortalBounds> candidates)
    {
        return findClosestPortalIndex(sourceX, sourceY, sourceZ, target,
                                      destinationBorder, candidates, null);
    }

    static int findClosestPortalIndex(double sourceX, double sourceY, double sourceZ,
                                      TargetDimension target, WorldBorder destinationBorder,
                                      List<PortalBounds> candidates, @Nullable int[] candidateIndices)
    {
        // Equivalent to WorldBorder.clampToBounds(), kept allocation-free
        // because exact zone generation evaluates this millions of times.
        int searchX = floor(clamp(
                sourceX * target.scale(), destinationBorder.getMinX(),
                destinationBorder.getMaxX() - WORLD_BORDER_EPSILON));
        int searchY = floor(sourceY);
        int searchZ = floor(clamp(
                sourceZ * target.scale(), destinationBorder.getMinZ(),
                destinationBorder.getMaxZ() - WORLD_BORDER_EPSILON));

        int borderMinX = (int) Math.ceil(destinationBorder.getMinX());
        int borderMaxX = (int) Math.ceil(destinationBorder.getMaxX()) - 1;
        int borderMinZ = (int) Math.ceil(destinationBorder.getMinZ());
        int borderMaxZ = (int) Math.ceil(destinationBorder.getMaxZ()) - 1;
        int candidateCount = candidateIndices != null ? candidateIndices.length : candidates.size();
        int bestIndex = NO_PORTAL;
        int bestY = Integer.MAX_VALUE;
        long bestDistance = Long.MAX_VALUE;

        for (int i = 0; i < candidateCount; ++i)
        {
            int candidateIndex = candidateIndices != null ? candidateIndices[i] : i;

            if (candidateIndex < 0 || candidateIndex >= candidates.size())
            {
                continue;
            }

            PortalBounds bounds = candidates.get(candidateIndex);
            int minX = Math.max(bounds.getMinX(), borderMinX);
            int maxX = Math.min(bounds.getMaxX(), borderMaxX);
            int minZ = Math.max(bounds.getMinZ(), borderMinZ);
            int maxZ = Math.min(bounds.getMaxZ(), borderMaxZ);

            if (minX > maxX || minZ > maxZ ||
                maxX < searchX - target.searchRadius() ||
                minX > searchX + target.searchRadius() ||
                maxZ < searchZ - target.searchRadius() ||
                minZ > searchZ + target.searchRadius())
            {
                continue;
            }

            // A portal is a filled axis-aligned rectangle. Clamping the search
            // position to its bounds gives the closest portal POI without
            // iterating every portal block for every rendered source block.
            int portalX = clamp(searchX, minX, maxX);
            int portalY = clamp(searchY, bounds.getMinY(), bounds.getMaxY());
            int portalZ = clamp(searchZ, minZ, maxZ);
            long dx = (long) portalX - searchX;
            long dy = (long) portalY - searchY;
            long dz = (long) portalZ - searchZ;
            long distance = dx * dx + dy * dy + dz * dz;

            if (distance < bestDistance || distance == bestDistance && portalY < bestY)
            {
                bestDistance = distance;
                bestY = portalY;
                bestIndex = candidateIndex;
            }
            // If distance and Y are both equal, retain encounter order, just
            // like Comparator.min() in PortalForcer.
        }

        return bestIndex;
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private static int floor(double value)
    {
        return (int) Math.floor(value);
    }

    record TargetDimension(String dimensionId, double scale, int searchRadius)
    {
    }
}
