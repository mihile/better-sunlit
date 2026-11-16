package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ClientLanguagePerf;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Parallelizes language resource reads and JSON parsing inside {@link ClientLanguage#m_264420_}
 * while preserving exact merge ordering and downstream mod Mixin hooks.
 */
@Mixin(value = ClientLanguage.class, remap = false)
public abstract class PerfClientLanguageMixin {
    @Shadow
    private static void m_235035_(String langCode, List<Resource> resources, Map<String, String> target) {
        throw new AssertionError();
    }

    @Inject(method = "m_264420_", at = @At("HEAD"))
    private static void sunlit$beginParallelLangLoad(
            ResourceManager resourceManager,
            List<String> filenames,
            boolean defaultRightToLeft,
            CallbackInfoReturnable<ClientLanguage> cir) {
        ClientLanguagePerf.beginSession(resourceManager, filenames);
    }

    @Redirect(
            method = "m_264420_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/resources/ResourceManager;m_213829_(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/List;"))
    private static List<Resource> sunlit$skipSequentialLangLookup(
            ResourceManager resourceManager, ResourceLocation location) {
        return ClientLanguagePerf.interceptGetResourceStack(resourceManager, location);
    }

    @Redirect(
            method = "m_264420_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/language/ClientLanguage;m_235035_(Ljava/lang/String;Ljava/util/List;Ljava/util/Map;)V"))
    private static void sunlit$applyPreloadedLangEntries(
            String langCode, List<Resource> resources, Map<String, String> target) {
        if (!ClientLanguagePerf.tryApplyPreloaded(langCode, resources, target)) {
            m_235035_(langCode, resources, target);
        }
    }

    @Inject(method = "m_264420_", at = @At("RETURN"))
    private static void sunlit$endParallelLangLoad(
            ResourceManager resourceManager,
            List<String> filenames,
            boolean defaultRightToLeft,
            CallbackInfoReturnable<ClientLanguage> cir) {
        ClientLanguagePerf.endSession();
    }
}
