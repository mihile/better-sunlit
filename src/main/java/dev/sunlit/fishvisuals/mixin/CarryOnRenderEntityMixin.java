package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.CarriedEntityRenderHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import tschipp.carryon.common.carry.CarryOnData;

@Pseudo
@Mixin(targets = "tschipp.carryon.client.render.CarryRenderHelper", remap = false)
public abstract class CarryOnRenderEntityMixin {
    @Redirect(method = "getRenderEntity(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/entity/Entity;",
            at = @At(value = "INVOKE",
                    target = "Ltschipp/carryon/common/carry/CarryOnData;getEntity(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/entity/Entity;"),
            require = 1)
    private static Entity sunlit$reuseLivingModel(CarryOnData carry, Level level, Player player) {
        if (player instanceof CarriedEntityRenderHolder holder) {
            return holder.sunlit$carriedRenderEntity(carry, level);
        }
        return carry.getEntity(level);
    }
}
