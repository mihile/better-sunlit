package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.SparkKeyStorePerf;
import me.lucko.spark.common.ws.TrustedKeyStore;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Defers Spark's eager WebSocket RSA/EC {@link KeyPair} generation until either needed by viewer
 * authentication or the post-startup delay elapses.
 */
@Mixin(value = TrustedKeyStore.class, remap = false)
public abstract class PerfSparkTrustedKeyStoreMixin {
    @Shadow
    @Final
    private CompletableFuture<KeyPair> localKeyPair;

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<KeyPair> sunlit$deferKeyPairGeneration(Supplier<KeyPair> supplier) {
        return SparkKeyStorePerf.deferKeyPair(supplier);
    }

    @Inject(method = "getLocalPublicKey", at = @At("HEAD"))
    private void sunlit$ensurePublicKeyReady(CallbackInfoReturnable<PublicKey> cir) {
        SparkKeyStorePerf.ensureCompleted(localKeyPair);
    }

    @Inject(method = "getLocalPrivateKey", at = @At("HEAD"))
    private void sunlit$ensurePrivateKeyReady(CallbackInfoReturnable<PrivateKey> cir) {
        SparkKeyStorePerf.ensureCompleted(localKeyPair);
    }
}
