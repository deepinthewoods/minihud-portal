package ninja.trek.gui.widgets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import ninja.trek.portal.PortalDataStore;
import ninja.trek.portal.PortalEntry;

public class WidgetListPortals extends WidgetListBase<PortalEntry, WidgetPortalEntry>
{
    public WidgetListPortals(int x, int y, int width, int height)
    {
        super(x, y, width, height, null);
        this.browserEntryHeight = 24;
    }

    @Override
    protected Collection<PortalEntry> getAllEntries()
    {
        List<PortalEntry> entries = new ArrayList<>(PortalDataStore.getInstance().getPortals());
        entries.sort(Comparator.comparing(PortalEntry::getDimensionId)
                               .thenComparing(e -> e.getBounds().getMinY())
                               .thenComparing(e -> e.getBounds().getMinX())
                               .thenComparing(e -> e.getBounds().getMinZ()));
        return entries;
    }

    @Override
    protected WidgetPortalEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, PortalEntry entry)
    {
        return new WidgetPortalEntry(x, y, this.browserEntryWidth, this.getBrowserEntryHeightFor(entry),
                isOdd, entry, listIndex, this);
    }
}
