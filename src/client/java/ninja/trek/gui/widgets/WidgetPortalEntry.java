package ninja.trek.gui.widgets;

import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetColorIndicator;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import ninja.trek.portal.PortalDataStore;
import ninja.trek.portal.PortalEntry;

public class WidgetPortalEntry extends WidgetListEntryBase<PortalEntry>
{
    private static final int COLOR_SIZE = 16;
    private static final int FIELD_HEIGHT = 16;

    private final PortalEntry portal;
    private final boolean isOdd;
    private final GuiTextFieldGeneric aliasField;
    private final WidgetColorIndicator colorIndicator;
    private final int aliasFieldWidth;

    public WidgetPortalEntry(int x, int y, int width, int height, boolean isOdd,
            PortalEntry portal, int listIndex, WidgetListPortals parent)
    {
        super(x, y, width, height, portal, listIndex);

        this.portal = portal;
        this.isOdd = isOdd;
        this.aliasFieldWidth = Math.min(160, Math.max(80, width - 160));
        this.aliasField = new GuiTextFieldGeneric(0, 0, this.aliasFieldWidth, FIELD_HEIGHT, this.textRenderer);
        this.aliasField.setMaxLengthWrapper(32);
        this.aliasField.setTextWrapper(portal.getAlias());

        this.colorIndicator = this.addWidget(new WidgetColorIndicator(0, 0, COLOR_SIZE, COLOR_SIZE,
                Color4f.fromColor(portal.getColor(), 1.0f), (color) -> {
                    this.portal.setColor(color);
                    PortalDataStore.getInstance().markDirty();
                }));
    }

    @Override
    public boolean onMouseClicked(Click click, boolean mouseReleased)
    {
        boolean handled = this.aliasField.mouseClickedWrapper(click, mouseReleased);

        if (handled)
        {
            this.applyAliasFromField();
            return true;
        }

        if (this.aliasField.isFocusedWrapper())
        {
            this.aliasField.setFocusedWrapper(false);
        }

        return super.onMouseClicked(click, mouseReleased);
    }

    @Override
    public boolean onKeyTyped(KeyInput key)
    {
        if (this.aliasField.isFocusedWrapper() && this.aliasField.keyPressedWrapper(key))
        {
            this.applyAliasFromField();
            return true;
        }

        return super.onKeyTyped(key);
    }

    @Override
    public boolean onCharTyped(CharInput chr)
    {
        if (this.aliasField.isFocusedWrapper() && this.aliasField.charTypedWrapper(chr))
        {
            this.applyAliasFromField();
            return true;
        }

        return super.onCharTyped(chr);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, boolean selected)
    {
        if (selected || this.isMouseOver(mouseX, mouseY))
        {
            RenderUtils.drawRect(context, this.x, this.y, this.width, this.height, 0x70FFFFFF);
        }
        else if (this.isOdd)
        {
            RenderUtils.drawRect(context, this.x, this.y, this.width, this.height, 0x20FFFFFF);
        }
        else
        {
            RenderUtils.drawRect(context, this.x, this.y, this.width, this.height, 0x50FFFFFF);
        }

        String coords = this.portal.getDisplayCoords();
        String uid = this.portal.getShortId();
        String label = coords + "  [" + uid + "]";

        this.drawString(context, this.x + 4, this.y + 7, 0xFFFFFFFF, label);

        this.updateWidgetPositions();
        super.render(context, mouseX, mouseY, selected);
        this.aliasField.renderWrapper(context, mouseX, mouseY, 0.0f);
    }

    @Override
    public boolean canSelectAt(Click click)
    {
        return super.canSelectAt(click) && this.aliasField.isMouseOver((int) click.x(), (int) click.y()) == false;
    }

    private void updateWidgetPositions()
    {
        int aliasX = this.x + this.width - this.aliasFieldWidth - COLOR_SIZE - 8;
        int aliasY = this.y + 4;

        this.aliasField.setXWrapper(aliasX);
        this.aliasField.setYWrapper(aliasY);

        this.colorIndicator.setX(aliasX + this.aliasFieldWidth + 4);
        this.colorIndicator.setY(this.y + 4);
    }

    private void applyAliasFromField()
    {
        String newAlias = this.aliasField.getTextWrapper();

        if (newAlias.equals(this.portal.getAlias()) == false)
        {
            this.portal.setAlias(newAlias);
            PortalDataStore.getInstance().markDirty();
        }
    }
}
