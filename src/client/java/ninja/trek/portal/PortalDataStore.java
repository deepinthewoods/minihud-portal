package ninja.trek.portal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.JsonUtils;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

public class PortalDataStore
{
    private static final PortalDataStore INSTANCE = new PortalDataStore();
    private static final int DATA_VERSION = 2;

    private final List<PortalEntry> portals = new ArrayList<>();
    private final PortalZoneSettings zoneSettings = new PortalZoneSettings();
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean dirty;

    public static PortalDataStore getInstance()
    {
        return INSTANCE;
    }

    public List<PortalEntry> getPortals()
    {
        return Collections.unmodifiableList(this.portals);
    }

    public PortalZoneSettings getZoneSettings()
    {
        return this.zoneSettings;
    }

    public void addListener(Runnable listener)
    {
        if (listener != null && this.listeners.contains(listener) == false)
        {
            this.listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener)
    {
        this.listeners.remove(listener);
    }

    public void clear()
    {
        this.portals.clear();
        this.zoneSettings.reset();
        this.dirty = false;
        this.notifyListeners();
    }

    public void load()
    {
        this.portals.clear();
        this.dirty = false;

        Path file = this.getStorageFile();
        JsonElement element = JsonUtils.parseJsonFileAsPath(file);

        if (element != null && element.isJsonObject())
        {
            JsonObject root = element.getAsJsonObject();
            JsonArray arr = root.has("portals") ? root.getAsJsonArray("portals") : new JsonArray();

            if (root.has("zone_settings") && root.get("zone_settings").isJsonObject())
            {
                this.zoneSettings.fromJson(root.getAsJsonObject("zone_settings"));
            }

            for (JsonElement entryEl : arr)
            {
                if (entryEl.isJsonObject())
                {
                    PortalEntry entry = this.portalFromJson(entryEl.getAsJsonObject());

                    if (entry != null)
                    {
                        this.portals.add(entry);
                    }
                }
            }
        }

        this.notifyListeners();
    }

    public void save()
    {
        if (this.dirty == false)
        {
            return;
        }

        Path file = this.getStorageFile();
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();

        for (PortalEntry entry : this.portals)
        {
            arr.add(this.portalToJson(entry));
        }

        root.add("version", new JsonPrimitive(DATA_VERSION));
        root.add("zone_settings", this.zoneSettings.toJson());
        root.add("portals", arr);

        JsonUtils.writeJsonToFileAsPath(root, file);
        this.dirty = false;
    }

    public void markDirty()
    {
        this.dirty = true;
    }

    public void markDirtyAndNotify()
    {
        this.dirty = true;
        this.notifyListeners();
    }

    public Optional<PortalEntry> findMatching(String dimensionId, PortalBounds bounds)
    {
        PortalEntry best = null;

        for (PortalEntry entry : this.portals)
        {
            if (entry.getDimensionId().equals(dimensionId) && entry.getBounds().intersects(bounds))
            {
                best = entry;
                break;
            }
        }

        return Optional.ofNullable(best);
    }

    public boolean intersectsTrackedPortal(String dimensionId, BlockPos pos)
    {
        for (PortalEntry entry : this.portals)
        {
            if (entry.getDimensionId().equals(dimensionId))
            {
                PortalBounds bounds = entry.getBounds();

                if (pos.getX() >= bounds.getMinX() && pos.getX() <= bounds.getMaxX() &&
                    pos.getY() >= bounds.getMinY() && pos.getY() <= bounds.getMaxY() &&
                    pos.getZ() >= bounds.getMinZ() && pos.getZ() <= bounds.getMaxZ())
                {
                    return true;
                }
            }
        }

        return false;
    }

    public void updateFromSnapshots(String dimensionId, ChunkPos chunkPos, List<PortalSnapshot> snapshots, World world)
    {
        boolean changed = false;
        List<PortalEntry> matched = new ArrayList<>();

        for (PortalSnapshot snapshot : snapshots)
        {
            PortalBounds bounds = snapshot.bounds();
            PortalEntry entry = this.findMatching(dimensionId, bounds).orElse(null);

            if (entry == null)
            {
                entry = new PortalEntry(UUID.randomUUID(), dimensionId, bounds, "", PortalColors.defaultColor(bounds));
                this.portals.add(entry);
                changed = true;
            }
            else if (entry.getBounds().equals(bounds) == false)
            {
                entry.setBounds(bounds);
                changed = true;
            }

            matched.add(entry);
        }

        List<PortalEntry> removals = new ArrayList<>();

        for (PortalEntry entry : this.portals)
        {
            if (entry.getDimensionId().equals(dimensionId) &&
                entry.getBounds().intersectsChunk(chunkPos) &&
                matched.contains(entry) == false)
            {
                if (world == null || this.portalExistsInBounds(world, entry.getBounds()) == false)
                {
                    removals.add(entry);
                }
            }
        }

        if (removals.isEmpty() == false)
        {
            this.portals.removeAll(removals);
            changed = true;
        }

        if (changed)
        {
            this.dirty = true;
            this.notifyListeners();
        }
    }

    private JsonObject portalToJson(PortalEntry entry)
    {
        PortalBounds bounds = entry.getBounds();
        JsonObject obj = new JsonObject();

        obj.add("id", new JsonPrimitive(entry.getId().toString()));
        obj.add("dimension", new JsonPrimitive(entry.getDimensionId()));
        obj.add("min_x", new JsonPrimitive(bounds.getMinX()));
        obj.add("min_y", new JsonPrimitive(bounds.getMinY()));
        obj.add("min_z", new JsonPrimitive(bounds.getMinZ()));
        obj.add("max_x", new JsonPrimitive(bounds.getMaxX()));
        obj.add("max_y", new JsonPrimitive(bounds.getMaxY()));
        obj.add("max_z", new JsonPrimitive(bounds.getMaxZ()));
        obj.add("alias", new JsonPrimitive(entry.getAlias()));
        obj.add("color", new JsonPrimitive(entry.getColor()));

        return obj;
    }

    private PortalEntry portalFromJson(JsonObject obj)
    {
        String idStr = JsonUtils.getStringOrDefault(obj, "id", "");
        String dimension = JsonUtils.getStringOrDefault(obj, "dimension", "");
        int minX = JsonUtils.getIntegerOrDefault(obj, "min_x", 0);
        int minY = JsonUtils.getIntegerOrDefault(obj, "min_y", 0);
        int minZ = JsonUtils.getIntegerOrDefault(obj, "min_z", 0);
        int maxX = JsonUtils.getIntegerOrDefault(obj, "max_x", 0);
        int maxY = JsonUtils.getIntegerOrDefault(obj, "max_y", 0);
        int maxZ = JsonUtils.getIntegerOrDefault(obj, "max_z", 0);
        String alias = JsonUtils.getStringOrDefault(obj, "alias", "");
        int color = JsonUtils.getIntegerOrDefault(obj, "color", 0xFF00A0FF);

        if (idStr.isEmpty() || dimension.isEmpty())
        {
            return null;
        }

        try
        {
            UUID id = UUID.fromString(idStr);
            PortalBounds bounds = new PortalBounds(minX, minY, minZ, maxX, maxY, maxZ);
            return new PortalEntry(id, dimension, bounds, alias, color);
        }
        catch (IllegalArgumentException ignore)
        {
            return null;
        }
    }

    private Path getStorageFile()
    {
        Path saveDir = FileUtils.getConfigDirectoryAsPath().resolve("minihud-portal");

        if (!Files.exists(saveDir))
        {
            FileUtils.createDirectoriesIfMissing(saveDir);
        }

        String fileName = StringUtils.getStorageFileName(true, "", ".json", "minihud-portal_default");
        return saveDir.resolve(fileName);
    }

    private void notifyListeners()
    {
        for (Runnable listener : this.listeners)
        {
            listener.run();
        }
    }

    private boolean portalExistsInBounds(World world, PortalBounds bounds)
    {
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();

        for (int y = bounds.getMinY(); y <= bounds.getMaxY(); ++y)
        {
            for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); ++z)
            {
                for (int x = bounds.getMinX(); x <= bounds.getMaxX(); ++x)
                {
                    int chunkX = x >> 4;
                    int chunkZ = z >> 4;
                    WorldChunk chunk = (WorldChunk) world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);

                    if (chunk == null)
                    {
                        return true;
                    }

                    mutablePos.set(x, y, z);

                    if (chunk.getBlockState(mutablePos).isOf(net.minecraft.block.Blocks.NETHER_PORTAL))
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
