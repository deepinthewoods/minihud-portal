package ninja.trek.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import fi.dy.masa.minihud.mixin.world.IMixinChunkDeltaUpdateS2CPacket;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.ChunkSectionPos;
import ninja.trek.portal.PortalScanner;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class MixinClientPlayNetworkHandler
{
    @Inject(method = "onChunkData", at = @At("RETURN"))
    private void minihudportal_onChunkData(ChunkDataS2CPacket packet, CallbackInfo ci)
    {
        PortalScanner.getInstance().onChunkLoaded(packet.getChunkX(), packet.getChunkZ());
    }

    @Inject(method = "onBlockUpdate", at = @At("RETURN"))
    private void minihudportal_onBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci)
    {
        PortalScanner.getInstance().onBlockUpdate(packet.getPos(), packet.getState());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("RETURN"))
    private void minihudportal_onChunkDeltaUpdate(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci)
    {
        ChunkSectionPos pos = ((IMixinChunkDeltaUpdateS2CPacket) packet).minihud_getChunkSectionPos();
        PortalScanner.getInstance().onChunkLoaded(pos.getSectionX(), pos.getSectionZ());
    }
}
