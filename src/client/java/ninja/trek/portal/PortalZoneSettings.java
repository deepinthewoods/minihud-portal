package ninja.trek.portal;

import com.google.gson.JsonObject;
import fi.dy.masa.malilib.util.JsonUtils;

public class PortalZoneSettings
{
    private boolean showZoneBorders;
    private boolean renderLines;
    private boolean renderThrough;
    private boolean renderLetters;

    public boolean isShowZoneBorders()
    {
        return this.showZoneBorders;
    }

    public boolean shouldRenderLines()
    {
        return this.renderLines;
    }

    public boolean shouldRenderThrough()
    {
        return this.renderThrough;
    }

    public boolean shouldRenderLetters()
    {
        return this.renderLetters;
    }

    public void setShowZoneBorders(boolean showZoneBorders)
    {
        this.showZoneBorders = showZoneBorders;
    }

    public void setRenderLines(boolean renderLines)
    {
        this.renderLines = renderLines;
    }

    public void setRenderThrough(boolean renderThrough)
    {
        this.renderThrough = renderThrough;
    }

    public void setRenderLetters(boolean renderLetters)
    {
        this.renderLetters = renderLetters;
    }

    public void toggleShowZoneBorders()
    {
        this.showZoneBorders = !this.showZoneBorders;
    }

    public void toggleRenderLines()
    {
        this.renderLines = !this.renderLines;
    }

    public void toggleRenderThrough()
    {
        this.renderThrough = !this.renderThrough;
    }

    public void toggleRenderLetters()
    {
        this.renderLetters = !this.renderLetters;
    }

    public void reset()
    {
        this.showZoneBorders = false;
        this.renderLines = false;
        this.renderThrough = false;
        this.renderLetters = false;
    }

    public JsonObject toJson()
    {
        JsonObject obj = new JsonObject();
        obj.addProperty("show_zone_borders", this.showZoneBorders);
        obj.addProperty("render_lines", this.renderLines);
        obj.addProperty("render_through", this.renderThrough);
        obj.addProperty("render_letters", this.renderLetters);
        return obj;
    }

    public void fromJson(JsonObject obj)
    {
        if (obj == null)
        {
            return;
        }

        this.showZoneBorders = JsonUtils.getBooleanOrDefault(obj, "show_zone_borders", this.showZoneBorders);
        this.renderLines = JsonUtils.getBooleanOrDefault(obj, "render_lines", this.renderLines);
        this.renderThrough = JsonUtils.getBooleanOrDefault(obj, "render_through", this.renderThrough);
        this.renderLetters = JsonUtils.getBooleanOrDefault(obj, "render_letters", this.renderLetters);
    }
}
