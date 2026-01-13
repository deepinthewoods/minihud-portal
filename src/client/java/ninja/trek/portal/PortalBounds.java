package ninja.trek.portal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

public final class PortalBounds
{
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public PortalBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
    }

    public int getMinX()
    {
        return this.minX;
    }

    public int getMinY()
    {
        return this.minY;
    }

    public int getMinZ()
    {
        return this.minZ;
    }

    public int getMaxX()
    {
        return this.maxX;
    }

    public int getMaxY()
    {
        return this.maxY;
    }

    public int getMaxZ()
    {
        return this.maxZ;
    }

    public BlockPos getMinPos()
    {
        return new BlockPos(this.minX, this.minY, this.minZ);
    }

    public BlockPos getMaxPos()
    {
        return new BlockPos(this.maxX, this.maxY, this.maxZ);
    }

    public boolean intersectsChunk(ChunkPos chunkPos)
    {
        int chunkMinX = chunkPos.getStartX();
        int chunkMinZ = chunkPos.getStartZ();
        int chunkMaxX = chunkMinX + 15;
        int chunkMaxZ = chunkMinZ + 15;

        return this.maxX >= chunkMinX && this.minX <= chunkMaxX &&
               this.maxZ >= chunkMinZ && this.minZ <= chunkMaxZ;
    }

    public boolean intersects(PortalBounds other)
    {
        return this.maxX >= other.minX && this.minX <= other.maxX &&
               this.maxY >= other.minY && this.minY <= other.maxY &&
               this.maxZ >= other.minZ && this.minZ <= other.maxZ;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }

        if (obj == null || this.getClass() != obj.getClass())
        {
            return false;
        }

        PortalBounds other = (PortalBounds) obj;

        return this.minX == other.minX &&
               this.minY == other.minY &&
               this.minZ == other.minZ &&
               this.maxX == other.maxX &&
               this.maxY == other.maxY &&
               this.maxZ == other.maxZ;
    }

    @Override
    public int hashCode()
    {
        int result = this.minX;
        result = 31 * result + this.minY;
        result = 31 * result + this.minZ;
        result = 31 * result + this.maxX;
        result = 31 * result + this.maxY;
        result = 31 * result + this.maxZ;
        return result;
    }
}
