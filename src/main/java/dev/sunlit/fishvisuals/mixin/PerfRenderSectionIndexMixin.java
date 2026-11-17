package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.RenderSectionIndexCapacity;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class PerfRenderSectionIndexMixin {
    @Redirect(method = "<init>", at = @At(value = "NEW", target = "it/unimi/dsi/fastutil/longs/Long2ReferenceOpenHashMap"), require = 1)
    private Long2ReferenceOpenHashMap<RenderSection> sunlit$sizeSectionIndex(ClientLevel world, int distance, CommandList commands) {
        return new Long2ReferenceOpenHashMap<>(RenderSectionIndexCapacity.expected(distance, world.m_151559_()));
    }
}
