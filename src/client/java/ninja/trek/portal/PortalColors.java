package ninja.trek.portal;

import java.awt.Color;
import java.util.Random;

public final class PortalColors
{
    private static final Random RANDOM = new Random();

    private PortalColors()
    {
    }

    public static int defaultColor(PortalBounds bounds)
    {
        float hue = RANDOM.nextFloat();
        float saturation = 0.6f + RANDOM.nextFloat() * 0.3f;
        float brightness = 0.8f + RANDOM.nextFloat() * 0.2f;
        int rgb = Color.HSBtoRGB(hue, saturation, brightness);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }

    public static int randomColor()
    {
        float hue = RANDOM.nextFloat();
        float saturation = 0.6f + RANDOM.nextFloat() * 0.3f;
        float brightness = 0.8f + RANDOM.nextFloat() * 0.2f;
        int rgb = Color.HSBtoRGB(hue, saturation, brightness);
        return 0xFF000000 | (rgb & 0x00FFFFFF);
    }
}
