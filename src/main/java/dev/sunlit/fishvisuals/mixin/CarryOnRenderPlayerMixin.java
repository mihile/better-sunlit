package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.CarriedEntityRenderHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tschipp.carryon.common.carry.CarryOnData;
import tschipp.carryon.common.carry.CarryOnDataManager;

@Mixin(value = Player.class, remap = false)
public abstract class CarryOnRenderPlayerMixin implements CarriedEntityRenderHolder {
    @Unique private LivingEntity sunlit$renderedCarriedEntity;
    @Unique private CompoundTag sunlit$renderedCarriedTag;

    @Override
    public Entity sunlit$carriedRenderEntity(CarryOnData carry, Level level) {
        CompoundTag tag = carry.getContentNbt();
        boolean overridesEntity = carry.getActiveScript().map(script ->
                script.scriptRender().renderNameEntity().isPresent()
                        || script.scriptRender().renderNBT().isPresent()).orElse(false);
        if (!level.f_46443_ || tag == null || overridesEntity) {
            sunlit$clearCarriedModel();
            return carry.getEntity(level);
        }
        if (sunlit$renderedCarriedEntity != null && sunlit$renderedCarriedEntity.m_9236_() == level
                && sunlit$renderedCarriedTag.equals(tag)) {
            return sunlit$renderedCarriedEntity;
        }

        CompoundTag snapshot = tag.m_6426_();
        Entity entity = carry.getEntity(level);
        sunlit$clearCarriedModel();
        if (entity instanceof LivingEntity living) {
            // EMF stores animation variables on this instance between render passes.
            sunlit$renderedCarriedEntity = living;
            sunlit$renderedCarriedTag = snapshot;
        }
        return entity;
    }

    @Inject(method = "m_8119_()V", at = @At("TAIL"), require = 1)
    private void sunlit$discardChangedCarriedModel(CallbackInfo ci) {
        if (sunlit$renderedCarriedEntity == null) return;
        Player player = (Player) (Object) this;
        CompoundTag carry = player.m_20088_().m_135370_(CarryOnDataManager.CARRY_DATA_KEY);
        if (player.m_9236_() != sunlit$renderedCarriedEntity.m_9236_()
                || !"ENTITY".equals(carry.m_128461_("type"))
                || !sunlit$renderedCarriedTag.equals(carry.m_128423_("entity"))) {
            sunlit$clearCarriedModel();
        }
    }

    @Unique
    private void sunlit$clearCarriedModel() {
        sunlit$renderedCarriedEntity = null;
        sunlit$renderedCarriedTag = null;
    }
}
