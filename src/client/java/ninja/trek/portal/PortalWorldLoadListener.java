package ninja.trek.portal;

import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import ninja.trek.portal.PortalZoneRenderer;

public class PortalWorldLoadListener implements IWorldLoadListener
{
    @Override
    public void onWorldLoadImmutable(RegistryAccess.Frozen immutable)
    {
    }

    @Override
    public void onWorldLoadPre(ClientLevel worldBefore, ClientLevel worldAfter, Minecraft mc)
    {
        if (worldBefore != null && worldAfter == null)
        {
            PortalDataStore.getInstance().save();
        }
    }

    @Override
    public void onWorldLoadPost(ClientLevel worldBefore, ClientLevel worldAfter, Minecraft mc)
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
