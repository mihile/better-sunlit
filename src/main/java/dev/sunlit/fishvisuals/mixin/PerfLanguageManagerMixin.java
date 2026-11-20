package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ClientLanguagePerf;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Overlaps {@code ClientLanguage} file reading and KubeJS lang JSON serialization with resource
 * reload preparation ({@code prepExecutor}) instead of stalling the post-barrier apply phase on
 * {@code main}.
 */
@Mixin(value = LanguageManager.class, remap = false)
public abstract class PerfLanguageManagerMixin implements ResourceManagerReloadListener {
    @Shadow
    private String f_118967_;

    @Override
    public CompletableFuture<Void> m_5540_(
            PreparableReloadListener.PreparationBarrier barrier,
            ResourceManager resourceManager,
            ProfilerFiller prepProfiler,
            ProfilerFiller applyProfiler,
            Executor prepExecutor,
            Executor applyExecutor) {
        String code = this.f_118967_;
        List<String> expectedLangs = (code == null || "en_us".equals(code))
                ? List.of("en_us")
                : List.of("en_us", code);
        return ClientLanguagePerf.prepareSessionAsync(resourceManager, expectedLangs, prepExecutor)
                .thenCompose(barrier::m_6769_)
                .thenRunAsync(() -> this.m_6213_(resourceManager), applyExecutor);
    }
}
