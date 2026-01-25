package ninja.trek.portal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

public class PortalScanner
{
    private static final PortalScanner INSTANCE = new PortalScanner();
    private static final int MAX_SCANS_PER_TICK = 1;
    private static final EnumSet<Direction> NEIGHBORS = EnumSet.allOf(Direction.class);

    private final LongOpenHashSet queuedChunks = new LongOpenHashSet();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

    public static PortalScanner getInstance()
    {
        return INSTANCE;
    }

    public void reset()
    {
        this.queuedChunks.clear();
        this.queue.clear();
    }

    public void onChunkLoaded(int chunkX, int chunkZ)
    {
        if (PortalDataStore.getInstance().getZoneSettings().isPortalScanningDisabled())
        {
            return;
        }
        this.enqueueChunk(new ChunkPos(chunkX, chunkZ));
    }

    public void onBlockUpdate(BlockPos pos, BlockState state)
    {
        if (PortalDataStore.getInstance().getZoneSettings().isPortalScanningDisabled())
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        World world = mc.world;

        if (world == null)
        {
            return;
        }

        String dimensionId = world.getRegistryKey().getValue().toString();

        if (state.isOf(Blocks.NETHER_PORTAL) ||
            PortalDataStore.getInstance().intersectsTrackedPortal(dimensionId, pos))
        {
            this.enqueueChunk(new ChunkPos(pos));
        }
    }

    public void tick(MinecraftClient mc)
    {
        if (mc.world == null || this.queue.isEmpty() || PortalDataStore.getInstance().getZoneSettings().isPortalScanningDisabled())
        {
            return;
        }

        for (int i = 0; i < MAX_SCANS_PER_TICK && this.queue.isEmpty() == false; ++i)
        {
            long packed = this.queue.dequeueLong();
            this.queuedChunks.remove(packed);
            ChunkPos chunkPos = new ChunkPos(packed);
            this.scanChunk(mc.world, chunkPos);
        }
    }

    private void enqueueChunk(ChunkPos chunkPos)
    {
        long packed = chunkPos.toLong();

        if (this.queuedChunks.add(packed))
        {
            this.queue.enqueue(packed);
        }
    }

    private void scanChunk(World world, ChunkPos chunkPos)
    {
        List<PortalSnapshot> snapshots = new ArrayList<>();
        LongOpenHashSet visited = new LongOpenHashSet();
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();

        int minY = world.getBottomY();
        int maxY = world.getTopYInclusive();
        int startX = chunkPos.getStartX();
        int startZ = chunkPos.getStartZ();

        for (int y = minY; y <= maxY; y += 3)
        {
            for (int z = 0; z < 16; ++z)
            {
                for (int x = 0; x < 16; ++x)
                {
                    mutablePos.set(startX + x, y, startZ + z);
                    long packed = mutablePos.asLong();

                    if (visited.contains(packed))
                    {
                        continue;
                    }

                    BlockState state = this.getBlockStateIfLoaded(world, mutablePos);

                    if (state != null && state.isOf(Blocks.NETHER_PORTAL))
                    {
                        PortalSnapshot snapshot = this.explorePortal(world, mutablePos.toImmutable(), visited);

                        if (snapshot != null)
                        {
                            snapshots.add(snapshot);
                        }
                    }
                }
            }
        }

        String dimensionId = world.getRegistryKey().getValue().toString();
        PortalDataStore.getInstance().updateFromSnapshots(dimensionId, chunkPos, snapshots, world);
    }

    private PortalSnapshot explorePortal(World world, BlockPos start, LongOpenHashSet visited)
    {
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);

        int minX = start.getX();
        int minY = start.getY();
        int minZ = start.getZ();
        int maxX = start.getX();
        int maxY = start.getY();
        int maxZ = start.getZ();
        boolean found = false;

        while (queue.isEmpty() == false)
        {
            BlockPos pos = queue.removeFirst();
            long packed = pos.asLong();

            if (visited.contains(packed))
            {
                continue;
            }
            BlockState state = this.getBlockStateIfLoaded(world, pos);

            if (state == null || state.isOf(Blocks.NETHER_PORTAL) == false)
            {
                continue;
            }

            visited.add(packed);
            found = true;

            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());

            for (Direction dir : NEIGHBORS)
            {
                BlockPos next = pos.offset(dir);
                queue.addLast(next);
            }
        }

        if (found == false)
        {
            return null;
        }

        return new PortalSnapshot(new PortalBounds(minX, minY, minZ, maxX, maxY, maxZ));
    }

    private BlockState getBlockStateIfLoaded(World world, BlockPos pos)
    {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        WorldChunk chunk = (WorldChunk) world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);

        if (chunk == null)
        {
            return null;
        }

        return chunk.getBlockState(pos);
    }
}
