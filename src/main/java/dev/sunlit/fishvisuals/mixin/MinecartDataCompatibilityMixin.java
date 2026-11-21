package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.MinecartDataCompatibility;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(value = SynchedEntityData.class, remap = false)
public abstract class MinecartDataCompatibilityMixin {
    @Shadow @Final private Entity f_135344_;
    @Shadow @Final private Int2ObjectMap<SynchedEntityData.DataItem<?>> f_135345_;

    @ModifyVariable(method = "m_135356_(Ljava/util/List;)V", at = @At("HEAD"),
            argsOnly = true, require = 1)
    private List<SynchedEntityData.DataValue<?>> sunlit$alignMinecartData(List<SynchedEntityData.DataValue<?>> values) {
        if (!(f_135344_ instanceof AbstractMinecart)) return values;
        return switch (f_135344_.getClass().getName()) {
            case "com.alc.moreminecarts.entities.TankCartEntity" ->
                    MinecartDataCompatibility.align(EntityDataSerializers.f_135042_, f_135345_, values);
            case "com.alc.moreminecarts.entities.FlagCartEntity" ->
                    MinecartDataCompatibility.align(EntityDataSerializers.f_135028_, f_135345_, values);
            default -> values;
        };
    }
}
