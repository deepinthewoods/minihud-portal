package ninja.trek.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.minihud.gui.GuiConfigs;
import fi.dy.masa.minihud.gui.GuiConfigs.ConfigGuiTab;
import fi.dy.masa.minihud.gui.GuiShapeManager;
import ninja.trek.gui.widgets.WidgetListPortals;
import ninja.trek.gui.widgets.WidgetPortalEntry;
import ninja.trek.portal.PortalDataStore;
import ninja.trek.portal.PortalEntry;

public class GuiPortals extends GuiListBase<PortalEntry, WidgetPortalEntry, WidgetListPortals>
{
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
