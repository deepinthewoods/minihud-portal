package ninja.trek.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.minihud.gui.GuiConfigs;
import fi.dy.masa.minihud.gui.GuiConfigs.ConfigGuiTab;
import fi.dy.masa.minihud.gui.GuiShapeManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ninja.trek.gui.widgets.WidgetListPortals;
import ninja.trek.gui.widgets.WidgetPortalEntry;
import ninja.trek.portal.PortalDataStore;
import ninja.trek.portal.PortalEntry;
import ninja.trek.portal.PortalZoneSettings;
import ninja.trek.portal.PortalZoneRenderer;

public class GuiPortals extends GuiListBase<Object, WidgetListEntryBase<Object>, WidgetListPortals>
{
    private static final Logger LOGGER = LogManager.getLogger("minihud-portal");
    private final Runnable dataListener;

    public GuiPortals()
    {
        super(10, 50);

        this.title = StringUtils.translate("minihud-portal.gui.title.portals");
        this.dataListener = () -> {
            if (this.getListWidget() != null)
            {
                this.getListWidget().refreshEntries();
            }
        };
    }

    @Override
    public void initGui()
    {
        super.initGui();

        this.clearButtons();
        this.createTabButtons();
        this.getListWidget().refreshEntries();

        PortalDataStore.getInstance().addListener(this.dataListener);
    }

    @Override
    protected void closeGui(boolean returnToParent)
    {
        PortalDataStore.getInstance().removeListener(this.dataListener);
        super.closeGui(returnToParent);
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight()
    {
        return this.getScreenHeight() - this.getListY() - 6;
    }

    @Override
    protected WidgetListPortals createListWidget(int listX, int listY)
    {
        return new WidgetListPortals(listX, listY, this.getBrowserWidth(), this.getBrowserHeight());
    }

    private void createTabButtons()
    {
        int x = 10;
        int y = 26;
        int rows = 1;

        for (ConfigGuiTab tab : ConfigGuiTab.values())
        {
            int width = this.getStringWidth(tab.getDisplayName()) + 10;

            if (x >= this.getScreenWidth() - width - 10)
            {
                x = 10;
                y += 22;
                ++rows;
            }

            x += this.createTabButton(x, y, width, tab);
        }

        String label = StringUtils.translate("minihud-portal.gui.button.config_gui.portals");
        int width = this.getStringWidth(label) + 10;

        if (x >= this.getScreenWidth() - width - 10)
        {
            x = 10;
            y += 22;
            ++rows;
        }

        ButtonGeneric portalsButton = new ButtonGeneric(x, y, width, 20, label);
        portalsButton.setEnabled(false);
        this.addButton(portalsButton, new ButtonListenerPortals());

        x += portalsButton.getWidth() + 2;

        PortalZoneSettings settings = PortalDataStore.getInstance().getZoneSettings();

        ButtonOnOff zoneBordersButton = new ButtonOnOff(0, 0, -1, false,
                "minihud-portal.gui.button.portal_zone_borders", settings.isShowZoneBorders());
        RowLayout layout = new RowLayout(x, y, rows);
        layout = this.placeTopRowButton(layout, zoneBordersButton);

        ButtonOnOff renderLinesButton = new ButtonOnOff(0, 0, -1, false,
                "minihud.gui.button.shape_renderer.toggle_render_lines", settings.shouldRenderLines());
        layout = this.placeTopRowButton(layout, renderLinesButton);

        ButtonOnOff renderThroughButton = new ButtonOnOff(0, 0, -1, false,
                "minihud.gui.button.shape_renderer.toggle_render_through", settings.shouldRenderThrough());
        layout = this.placeTopRowButton(layout, renderThroughButton);

        ButtonOnOff renderLettersButton = new ButtonOnOff(0, 0, -1, false,
                "minihud-portal.gui.button.portal_zone_letters", settings.shouldRenderLetters());
        layout = this.placeTopRowButton(layout, renderLettersButton);
        x = layout.x();
        y = layout.y();
        rows = layout.rows();

        this.addButton(zoneBordersButton, (btn, mouseBtn) -> {
            settings.toggleShowZoneBorders();
            zoneBordersButton.updateDisplayString(settings.isShowZoneBorders());
            PortalDataStore.getInstance().markDirty();
            PortalZoneRenderer.INSTANCE.onSettingsChanged();
            LOGGER.warn("Portal zone borders toggled via GUI: showZoneBorders={}", settings.isShowZoneBorders());
        });

        this.addButton(renderLinesButton, (btn, mouseBtn) -> {
            settings.toggleRenderLines();
            renderLinesButton.updateDisplayString(settings.shouldRenderLines());
            PortalDataStore.getInstance().markDirty();
            PortalZoneRenderer.INSTANCE.onSettingsChanged();
            LOGGER.warn("Portal zone borders render lines toggled via GUI: renderLines={}", settings.shouldRenderLines());
        });

        this.addButton(renderThroughButton, (btn, mouseBtn) -> {
            settings.toggleRenderThrough();
            renderThroughButton.updateDisplayString(settings.shouldRenderThrough());
            PortalDataStore.getInstance().markDirty();
            PortalZoneRenderer.INSTANCE.onSettingsChanged();
            LOGGER.warn("Portal zone borders render through toggled via GUI: renderThrough={}", settings.shouldRenderThrough());
        });

        this.addButton(renderLettersButton, (btn, mouseBtn) -> {
            settings.toggleRenderLetters();
            renderLettersButton.updateDisplayString(settings.shouldRenderLetters());
            PortalDataStore.getInstance().markDirty();
            PortalZoneRenderer.INSTANCE.onSettingsChanged();
            LOGGER.warn("Portal zone letters toggled via GUI: renderLetters={}", settings.shouldRenderLetters());
        });

        if (rows > 1)
        {
            int scrollbarPosition = this.getListWidget().getScrollbar().getValue();
            this.setListPosition(this.getListX(), 50 + (rows - 1) * 22);
            this.reCreateListWidget();
            this.getListWidget().getScrollbar().setValue(scrollbarPosition);
            this.getListWidget().refreshEntries();
        }
    }

    protected int createTabButton(int x, int y, int width, ConfigGuiTab tab)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, tab.getDisplayName());
        button.setEnabled(true);
        this.addButton(button, new GuiShapeManager.ButtonListenerTab(tab));

        return button.getWidth() + 2;
    }

    private RowLayout placeTopRowButton(RowLayout layout, ButtonOnOff button)
    {
        int x = layout.x();
        int y = layout.y();
        int rows = layout.rows();
        int width = button.getWidth();

        if (x >= this.getScreenWidth() - width - 10)
        {
            x = 10;
            y += 22;
            ++rows;
        }

        button.setPosition(x, y);
        return new RowLayout(x + width + 2, y, rows);
    }

    private record RowLayout(int x, int y, int rows)
    {
    }

    private static class ButtonListenerPortals implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            GuiConfigs.tab = ConfigGuiTab.INFO_LINES;
            GuiBase.openGui(new GuiPortals());
        }
    }
}
