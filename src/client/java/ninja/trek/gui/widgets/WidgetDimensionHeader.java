package ninja.trek.gui.widgets;

import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.RenderUtils;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import ninja.trek.gui.widgets.WidgetListPortals.DimensionHeader;

public class WidgetDimensionHeader extends WidgetListEntryBase<Object>
{
    private static final int HEADER_COLOR = 0xFF404040;
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    private final DimensionHeader header;

    public WidgetDimensionHeader(int x, int y, int width, int height, DimensionHeader header)
    {
        super(x, y, width, height, header, 0);
        this.header = header;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, boolean selected)
    {
        // Draw header background
        RenderUtils.drawRect(context, this.x, this.y, this.width, this.height, HEADER_COLOR);

        // Format dimension ID for display
        String displayName = formatDimensionName(this.header.dimensionId());

        // Draw dimension name
        int textX = this.x + 4;
        int textY = this.y + 6;
        context.drawText(this.textRenderer, Text.literal(displayName), textX, textY, TEXT_COLOR, false);
    }

    private String formatDimensionName(String dimensionId)
    {
        if (dimensionId == null)
        {
            return "Unknown";
        }

        return switch (dimensionId)
        {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "The End";
            default -> dimensionId;
        };
    }
}
