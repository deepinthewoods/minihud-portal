package ninja.trek.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.minihud.gui.GuiConfigs;
import fi.dy.masa.minihud.gui.GuiConfigs.ConfigGuiTab;
import ninja.trek.gui.GuiPortals;

@Mixin(GuiConfigs.class)
public abstract class MixinGuiConfigs extends GuiConfigsBase
{
    protected MixinGuiConfigs()
    {
        super(0, 0, null, null, null);
    }

    @Inject(method = "initGui", at = @At("TAIL"))
    private void minihudportal_addPortalsTab(CallbackInfo ci)
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
                rows++;
            }

            x += width + 2;
        }

        String label = StringUtils.translate("minihud-portal.gui.button.config_gui.portals");
        int width = this.getStringWidth(label) + 10;

        if (x >= this.getScreenWidth() - width - 10)
        {
            x = 10;
            y += 22;
            rows++;
        }

        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, label);
        this.addButton(button, (btn, mouseBtn) -> GuiBase.openGui(new GuiPortals()));

        if (rows > 1)
        {
            int scrollbarPosition = this.getListWidget().getScrollbar().getValue();
            this.setListPosition(this.getListX(), 50 + (rows - 1) * 22);
            this.reCreateListWidget();
            this.getListWidget().getScrollbar().setValue(scrollbarPosition);
            this.getListWidget().refreshEntries();
        }
    }
}
