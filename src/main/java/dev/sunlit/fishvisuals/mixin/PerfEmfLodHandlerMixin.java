package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.EmfRenderPerf;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import traben.entity_model_features.EMF;
import traben.entity_model_features.config.EMFConfig;
import traben.entity_model_features.mod_compat.IrisShadowPassDetection;
import traben.entity_model_features.models.animation.state.EMFEntityRenderState;
import traben.entity_model_features.models.animation.state.EMFState;
import traben.entity_model_features.utils.EMFEntity;
import traben.entity_model_features.utils.UEntityTypes;
import traben.entity_texture_features.ETF;

@Pseudo
@Mixin(targets = "traben.entity_model_features.utils.EMFLODHandler", priority = 2000, remap = false)
public abstract class PerfEmfLodHandlerMixin {

    @Shadow
    private static Boolean lodFrameSkipping;

    @Shadow
    private static int getLODFactorOfEntity(EMFEntityRenderState state) {
        return 0;
    }

    @Shadow
    private static EMFEntityRenderState emfState() {
        return null;
    }

    @Shadow
    private static EMFEntity emfEntity() {
        return null;
    }

    /**
     * @author better-sunlit
     * @reason Avoid String concatenation and HashMap/Integer boxing per entity LOD timer check
     */
    @Overwrite(remap = false)
    public static boolean isLODSkippingThisFrame(String modelId) {
        if (EMFState.isInGui || EMFState.isInShoulderMethod) {
            return false;
        }
        Boolean cached = lodFrameSkipping;
        if (cached != null) {
            return cached.booleanValue();
        }
        EMFConfig config = EMF.config().getConfig();
        if (ETF.IRIS_DETECTED
                && IrisShadowPassDetection.getInstance().inShadowPass()
                && config.animationFrameSkipDuringIrisShadowPass) {
            EMFEntity entity = emfEntity();
            if (!(entity instanceof Player player)
                    || !player.m_7578_()
                    || !Minecraft.m_91087_().f_91066_.m_92176_().m_90612_()) {
                return true;
            }
        }
        EMFEntityRenderState state = emfState();
        if (config.animationLODDistance == 0 || state == null) {
            return false;
        }
        EntityType<?> type = state.entityType();
        if (type == UEntityTypes.VILLAGER || type == UEntityTypes.HORSE || type == UEntityTypes.BLAZE) {
            return false;
        }
        int existing = EmfRenderPerf.getLodTimer(state.uuid(), modelId);
        int current = existing == Integer.MIN_VALUE ? 0 : existing;
        int next = current < 1 ? getLODFactorOfEntity(state) : current - 1;
        EmfRenderPerf.putLodTimer(state.uuid(), modelId, next);
        boolean skipping = next > 0;
        lodFrameSkipping = Boolean.valueOf(skipping);
        return skipping;
    }
}
