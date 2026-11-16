package dev.sunlit.fishvisuals.mixin;

import com.hrznstudio.emojiful.ClientEmojiHandler;
import com.hrznstudio.emojiful.Constants;
import dev.sunlit.bettersunlit.client.EmojifulAsyncPerf;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves Emojiful's synchronous GitHub YAML/JSON fetches ({@code preInitEmojis()}) off the Render thread
 * during {@code Minecraft.<init>} and installs {@code EmojiFontRenderer} once emojis are ready.
 */
@Mixin(value = ClientEmojiHandler.class, remap = false)
public abstract class PerfEmojifulClientHandlerMixin {
    @Shadow
    private static void preInitEmojis() {
    }

    @Shadow
    private static void initEmojis() {
    }

    @Shadow
    public static void indexEmojis() {
    }

    @Inject(method = "setup()V", at = @At("HEAD"), cancellable = true)
    private static void sunlit$asyncSetup(CallbackInfo ci) {
        ci.cancel();
        EmojifulAsyncPerf.startAsync(() -> {
            preInitEmojis();
            indexEmojis();
            Constants.LOG.info("Loaded {} emojis", Constants.EMOJI_LIST.size());
        }, () -> {
            Minecraft mc = Minecraft.m_91087_();
            if (mc != null) {
                mc.execute(PerfEmojifulClientHandlerMixin::initEmojis);
            }
        });
    }

    @Inject(method = "indexEmojis()V", at = @At("HEAD"))
    private static void sunlit$awaitBeforeIndex(CallbackInfo ci) {
        EmojifulAsyncPerf.awaitLoaded();
    }
}
