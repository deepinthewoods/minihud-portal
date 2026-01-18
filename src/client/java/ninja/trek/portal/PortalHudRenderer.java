package ninja.trek.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.profiler.Profiler;
import org.joml.Matrix4f;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.minihud.config.Configs;
import fi.dy.masa.minihud.data.DebugDataManager;

public class PortalHudRenderer implements IRenderer
{
    private static final PortalHudRenderer INSTANCE = new PortalHudRenderer();

    public static PortalHudRenderer getInstance()
    {
        return INSTANCE;
    }

    @Override
    public void onRenderGameOverlayPostAdvanced(DrawContext drawContext, float partialTicks, Profiler profiler, MinecraftClient mc)
    {
        if (Configs.Generic.MAIN_RENDERING_TOGGLE.getBooleanValue() == false ||
            DebugDataManager.getInstance().shouldShowDebugHudFix() ||
            mc.player == null || mc.options.hudHidden)
        {
            return;
        }

        if (Configs.Generic.REQUIRE_SNEAK.getBooleanValue() && mc.player.isSneaking() == false)
        {
            return;
        }

        if (Configs.Generic.REQUIRED_KEY.getKeybind().isKeybindHeld() == false)
        {
            return;
        }

        PortalLinkPreview.Preview preview = PortalLinkPreview.compute(mc);

        if (preview == null || preview.linkedPortals().isEmpty())
        {
            return;
        }

        List<String> lines = new ArrayList<>();

        for (PortalEntry entry : preview.linkedPortals())
        {
            lines.add(entry.getShortId());
        }

        if (lines.isEmpty())
        {
            return;
        }

        int x = Configs.Generic.TEXT_POS_X.getIntegerValue();
        int y = Configs.Generic.TEXT_POS_Y.getIntegerValue();
        int textColor = Configs.Colors.TEXT_COLOR.getIntegerValue();
        int bgColor = Configs.Colors.TEXT_BACKGROUND_COLOR.getIntegerValue();
        boolean useBackground = Configs.Generic.USE_TEXT_BACKGROUND.getBooleanValue();
        boolean useShadow = Configs.Generic.USE_FONT_SHADOW.getBooleanValue();

        RenderUtils.renderText(drawContext, x, y, Configs.Generic.FONT_SCALE.getDoubleValue(),
                               textColor, bgColor, HudAlignment.BOTTOM_RIGHT,
                               useBackground, useShadow, Configs.Generic.HUD_STATUS_EFFECTS_SHIFT.getBooleanValue(),
                               lines);
    }

    @Override
    public void onRenderWorldPreWeather(Framebuffer fb, Matrix4f posMatrix, Matrix4f projMatrix, Frustum frustum,
                                        Camera camera, BufferBuilderStorage buffers, Profiler profiler)
    {
    }

    @Override
    public void onRenderWorldLastAdvanced(Framebuffer fb, Matrix4f posMatrix, Matrix4f projMatrix, Frustum frustum,
                                          Camera camera, BufferBuilderStorage buffers, Profiler profiler)
    {
    }

    @Override
    public void onRenderTooltipLast(DrawContext drawContext, ItemStack stack, int x, int y)
    {
    }

    @Override
    public Supplier<String> getProfilerSectionSupplier()
    {
        return () -> "minihud-portal_portal_preview";
    }

    @Override
    public void onRenderTooltipComponentInsertFirst(Item.TooltipContext context, ItemStack stack, Consumer<Text> list)
    {
    }

    @Override
    public void onRenderTooltipComponentInsertMiddle(Item.TooltipContext context, ItemStack stack, Consumer<Text> list)
    {
    }

    @Override
    public void onRenderTooltipComponentInsertLast(Item.TooltipContext context, ItemStack stack, Consumer<Text> list)
    {
    }
}
