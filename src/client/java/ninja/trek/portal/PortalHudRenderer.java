package ninja.trek.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.network.chat.Component;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import com.mojang.blaze3d.pipeline.RenderTarget;
import fi.dy.masa.malilib.config.HudAlignment;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.render.GuiContext;
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
    public void onRenderGameOverlayPostAdvanced(GuiContext drawContext, float partialTicks, ProfilerFiller profiler)
    {
        Minecraft mc = Minecraft.getInstance();

        if (Configs.Generic.MAIN_RENDERING_TOGGLE.getBooleanValue() == false ||
            DebugDataManager.getInstance().shouldShowDebugHudFix() ||
            mc.player == null || mc.options.hideGui)
        {
            return;
        }

        if (Configs.Generic.REQUIRE_SNEAK.getBooleanValue() && mc.player.isShiftKeyDown() == false)
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
    public void onRenderWorldPreWeather(RenderTarget fb, Matrix4f posMatrix, Matrix4f projMatrix, Frustum frustum,
                                        Camera camera, RenderBuffers buffers, ProfilerFiller profiler)
    {
    }

    @Override
    public void onRenderWorldLastAdvanced(RenderTarget fb, Matrix4f posMatrix, Matrix4f projMatrix, Frustum frustum,
                                          Camera camera, RenderBuffers buffers, ProfilerFiller profiler)
    {
    }

    @Override
    public void onRenderTooltipLast(GuiContext drawContext, ItemStack stack, int x, int y)
    {
    }

    @Override
    public Supplier<String> getProfilerSectionSupplier()
    {
        return () -> "minihud-portal_portal_preview";
    }

    @Override
    public void onRenderTooltipComponentInsertFirst(Item.TooltipContext context, ItemStack stack, Consumer<Component> list)
    {
    }

    @Override
    public void onRenderTooltipComponentInsertMiddle(Item.TooltipContext context, ItemStack stack, Consumer<Component> list)
    {
    }

    @Override
    public void onRenderTooltipComponentInsertLast(Item.TooltipContext context, ItemStack stack, Consumer<Component> list)
    {
    }
}
