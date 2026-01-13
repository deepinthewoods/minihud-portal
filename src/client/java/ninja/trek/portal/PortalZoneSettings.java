package ninja.trek.portal;

import com.google.gson.JsonObject;
import fi.dy.masa.malilib.util.JsonUtils;

public class PortalZoneSettings
{
    private boolean showZoneBorders;
    private boolean renderLines;
    private boolean renderThrough;

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

    public void reset()
    {
        this.showZoneBorders = false;
        this.renderLines = false;
        this.renderThrough = false;
    }

    public JsonObject toJson()
    {
        JsonObject obj = new JsonObject();
        obj.addProperty("show_zone_borders", this.showZoneBorders);
        obj.addProperty("render_lines", this.renderLines);
        obj.addProperty("render_through", this.renderThrough);
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
    }
}
