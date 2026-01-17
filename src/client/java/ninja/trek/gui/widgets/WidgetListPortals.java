package ninja.trek.gui.widgets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import ninja.trek.portal.PortalDataStore;
import ninja.trek.portal.PortalEntry;

public class WidgetListPortals extends WidgetListBase<Object, WidgetListEntryBase<Object>>
{
    public WidgetListPortals(int x, int y, int width, int height)
    {
        super(x, y, width, height, null);
        this.browserEntryHeight = 24;
    }

    @Override
    protected Collection<Object> getAllEntries()
    {
        List<Object> entries = new ArrayList<>();
        List<PortalEntry> allPortals = new ArrayList<>(PortalDataStore.getInstance().getPortals());

        // Sort by dimension first, then by position
        allPortals.sort(Comparator.comparing(PortalEntry::getDimensionId)
                               .thenComparing(e -> e.getBounds().getMinY())
                               .thenComparing(e -> e.getBounds().getMinX())
                               .thenComparing(e -> e.getBounds().getMinZ()));

        // Group by dimension
        Map<String, List<PortalEntry>> byDimension = new LinkedHashMap<>();
        for (PortalEntry portal : allPortals)
        {
            byDimension.computeIfAbsent(portal.getDimensionId(), k -> new ArrayList<>()).add(portal);
        }

        // Add dimension headers and their portals
        for (Map.Entry<String, List<PortalEntry>> entry : byDimension.entrySet())
        {
            entries.add(new DimensionHeader(entry.getKey()));
            entries.addAll(entry.getValue());
        }

        return entries;
    }

    @Override
    protected WidgetListEntryBase<Object> createListEntryWidget(int x, int y, int listIndex, boolean isOdd, Object entry)
    {
        if (entry instanceof DimensionHeader header)
        {
            return new WidgetDimensionHeader(x, y, this.browserEntryWidth, 20, header);
        }
        else if (entry instanceof PortalEntry portal)
        {
            return new WidgetPortalEntry(x, y, this.browserEntryWidth, this.getBrowserEntryHeightFor(portal),
                    isOdd, portal, listIndex, this);
        }

        return null;
    }

    @Override
    protected int getBrowserEntryHeightFor(Object entry)
    {
        if (entry instanceof DimensionHeader)
        {
            return 20;
        }
        return super.getBrowserEntryHeightFor(entry);
    }

    public record DimensionHeader(String dimensionId) {}
}
