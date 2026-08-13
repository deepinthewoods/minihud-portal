package ninja.trek.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import ninja.trek.portal.PortalScanner;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPlayNetworkHandler
{
    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void minihudportal_onChunkData(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci)
    {
        PortalScanner.getInstance().onChunkLoaded(packet.getX(), packet.getZ());
    }

    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void minihudportal_onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci)
    {
        PortalScanner.getInstance().onBlockUpdate(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void minihudportal_onChunkDeltaUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci)
    {
        packet.runUpdates(PortalScanner.getInstance()::onBlockUpdate);
    }
}
