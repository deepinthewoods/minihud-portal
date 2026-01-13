package ninja.trek.portal;

import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.DynamicRegistryManager;
import ninja.trek.portal.PortalZoneRenderer;

public class PortalWorldLoadListener implements IWorldLoadListener
{
    @Override
    public void onWorldLoadImmutable(DynamicRegistryManager.Immutable immutable)
    {
    }

    @Override
    public void onWorldLoadPre(ClientWorld worldBefore, ClientWorld worldAfter, MinecraftClient mc)
    {
        if (worldBefore != null && worldAfter == null)
        {
            PortalDataStore.getInstance().save();
        }
    }

    @Override
    public void onWorldLoadPost(ClientWorld worldBefore, ClientWorld worldAfter, MinecraftClient mc)
    {
        if (worldAfter == null)
        {
            PortalDataStore.getInstance().clear();
            PortalScanner.getInstance().reset();
            PortalZoneRenderer.INSTANCE.resetState();
            return;
        }

        PortalScanner.getInstance().reset();
        PortalZoneRenderer.INSTANCE.resetState();

        if (worldBefore == null)
        {
            PortalDataStore.getInstance().load();
        }
    }
}
