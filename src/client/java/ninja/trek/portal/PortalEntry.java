package ninja.trek.portal;

import java.util.Locale;
import java.util.UUID;
import net.minecraft.util.math.BlockPos;

public class PortalEntry
{
    private final UUID id;
    private final String dimensionId;
    private PortalBounds bounds;
    private String alias;
    private int color;

    public PortalEntry(UUID id, String dimensionId, PortalBounds bounds, String alias, int color)
    {
        this.id = id;
        this.dimensionId = dimensionId;
        this.bounds = bounds;
        this.alias = alias == null ? "" : alias;
        this.color = color;
    }

    public UUID getId()
    {
        return this.id;
    }

    public String getDimensionId()
    {
        return this.dimensionId;
    }

    public PortalBounds getBounds()
    {
        return this.bounds;
    }

    public void setBounds(PortalBounds bounds)
    {
        this.bounds = bounds;
    }

    public String getAlias()
    {
        return this.alias;
    }

    public void setAlias(String alias)
    {
        this.alias = alias == null ? "" : alias;
    }

    public int getColor()
    {
        return this.color;
    }

    public void setColor(int color)
    {
        this.color = color;
    }

    public String getShortId()
    {
        String raw = this.id.toString().replace("-", "");
        return raw.substring(0, Math.min(8, raw.length())).toUpperCase(Locale.ROOT);
    }

    public String getDisplayCoords()
    {
        BlockPos min = this.bounds.getMinPos();
        return String.format(Locale.ROOT, "%d %d %d", min.getX(), min.getY(), min.getZ());
    }
}
