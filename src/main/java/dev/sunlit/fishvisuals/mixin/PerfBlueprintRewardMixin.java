package dev.sunlit.fishvisuals.mixin;

import com.teamabnormals.blueprint.client.RewardHandler;
import com.teamabnormals.blueprint.core.sonar.OnlineRequest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Delays Blueprint's non-essential cosmetic reward HTTP request until after the initial client
 * startup window so TLS handshakes and remote fetches do not consume startup worker bandwidth.
 */
@Mixin(value = RewardHandler.class, remap = false)
public abstract class PerfBlueprintRewardMixin {
    @Redirect(
            method = "clientSetup",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/teamabnormals/blueprint/core/sonar/OnlineRequest;request(Ljava/lang/String;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<InputStream> sunlit$deferRewardsFetch(
            String url, Executor executor) {
        CompletableFuture<InputStream> deferred = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(15L, TimeUnit.SECONDS).execute(() ->
                OnlineRequest.request(url, executor).whenComplete((stream, err) -> {
                    if (err != null) {
                        deferred.completeExceptionally(err);
                    } else {
                        deferred.complete(stream);
                    }
                }));
        return deferred;
    }
}
