package dev.sunlit.fishvisuals.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.sunlit.bettersunlit.client.EmfModelPrebakePerf;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import traben.entity_model_features.EMFManager;
import traben.entity_model_features.models.EMFModel_ID;
import traben.entity_model_features.models.jem_objects.EMFJemData;
import traben.entity_model_features.models.parts.EMFModelPartRoot;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Replaces unsynchronized {@code EMFManager} collections with thread-safe equivalents for
 * parallel model loading and short-circuits {@code injectIntoModelRootGetter} when prebaked.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.EMFManager", remap = false)
public abstract class PerfEmfManagerInitMixin implements dev.sunlit.bettersunlit.client.EmfLoadingContext.ConcurrentModelManager {
    @Mutable
    @Shadow
    @Final
    public Set<EMFModel_ID> modelsAnnounced;

    @Mutable
    @Shadow
    @Final
    public Map<String, EMFJemData> cache_JemDataByFileName;

    @Mutable
    @Shadow
    @Final
    public Set<String> EBE_JEMS_FOUND_LAST;

    @Mutable
    @Shadow
    @Final
    private Set<String> EBE_JEMS_FOUND;

    @Mutable
    @Shadow
    @Final
    public List<Exception> loadingExceptions;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sunlit$initConcurrentCollections(CallbackInfo ci) {
        this.modelsAnnounced = ConcurrentHashMap.newKeySet();
        // Keep null negative-cache values intact: ConcurrentHashMap rejects null values.
        this.cache_JemDataByFileName = Collections.synchronizedMap(new HashMap<>());
        this.EBE_JEMS_FOUND_LAST = ConcurrentHashMap.newKeySet();
        this.EBE_JEMS_FOUND = ConcurrentHashMap.newKeySet();
        this.loadingExceptions = new CopyOnWriteArrayList<>();
    }

    @WrapMethod(method = "setupAnimationsFromJemToModel", require = 1)
    private void sunlit$validateAnimationsSerially(
            EMFJemData jem, EMFModelPartRoot model, int variant, Operation<Void> original) {
        // EMF's expression evaluator reads a validation flag shared by all models on this manager.
        synchronized (this) {
            original.call(jem, model, variant);
        }
    }

    @Inject(method = "injectIntoModelRootGetter", at = @At("HEAD"), cancellable = true)
    private void sunlit$returnPrebakedModel(
            ModelLayerLocation layer,
            ModelPart vanillaModelPart,
            CallbackInfoReturnable<ModelPart> cir) {
        ModelPart prebaked = EmfModelPrebakePerf.consumePendingEmfResult();
        if (prebaked != null && !EmfModelPrebakePerf.hasSpecialContext()) {
            EMFManager.lastCreatedRootModelPart = (prebaked instanceof EMFModelPartRoot root) ? root : null;
            cir.setReturnValue(prebaked);
        }
    }
}
