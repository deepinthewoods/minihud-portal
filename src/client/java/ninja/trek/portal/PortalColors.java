package ninja.trek.portal;

import java.awt.Color;

public final class PortalColors
{
    private PortalColors()
    {
    }

    public static int defaultColor(PortalBounds bounds)
    {
        int hash = 31 * bounds.getMinX() + 37 * bounds.getMinY() + 41 * bounds.getMinZ();
        float hue = (hash & 0xFFFF) / 65535f;
        int rgb = Color.HSBtoRGB(hue, 0.6f, 1.0f);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }
}
