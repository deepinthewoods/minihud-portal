package ninja.trek;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import ninja.trek.portal.PortalScanner;
import ninja.trek.portal.PortalWorldLoadListener;

public class MinihudportalClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PortalWorldLoadListener listener = new PortalWorldLoadListener();
		WorldLoadHandler.getInstance().registerWorldLoadPreHandler(listener);
		WorldLoadHandler.getInstance().registerWorldLoadPostHandler(listener);

		ClientTickEvents.END_CLIENT_TICK.register(PortalScanner.getInstance()::tick);
	}
}
