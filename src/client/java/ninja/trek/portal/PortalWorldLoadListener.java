package ninja.trek.portal;

import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.DynamicRegistryManager;

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
            return;
        }

        PortalScanner.getInstance().reset();

        if (worldBefore == null)
        {
            PortalDataStore.getInstance().load();
        }
    }
}
