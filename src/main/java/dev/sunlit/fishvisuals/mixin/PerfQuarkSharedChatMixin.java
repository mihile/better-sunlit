package dev.sunlit.fishvisuals.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "org.violetmoon.quark.content.management.module.ItemSharingModule$Client", remap = false)
public abstract class PerfQuarkSharedChatMixin {
    @Shadow
    private static void render(Minecraft client, GuiGraphics graphics, String prefix, float offset,
                               float x, float y, Style style, int color) {
        throw new AssertionError();
    }

    @Inject(method = "renderItemForMessage", cancellable = true,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;m_91087_()Lnet/minecraft/client/Minecraft;"))
    private static void sunlit$scanSharedItem(GuiGraphics graphics, FormattedCharSequence line,
            float x, float y, int color, CallbackInfo callback) {
        StringBuilder prefix = new StringBuilder();
        line.m_13731_((index, style, codePoint) -> {
            int length = prefix.length();
            if (length >= 2 && prefix.charAt(length - 1) == ' ' && prefix.charAt(length - 2) == ' ') {
                HoverEvent hover = style.m_131186_();
                if (hover != null && hover.m_130820_() == HoverEvent.Action.f_130832_) {
                    Minecraft client = Minecraft.m_91087_();
                    float offset = codePoint == ' ' ? 0 : -(client.f_91062_.m_92895_(" ") / 2);
                    render(client, graphics, prefix.substring(0, length - 2), offset, x, y, style, color);
                }
                return false;
            }
            // Quark compares its delimiter against UTF-16 chars in its prefix buffer.
            prefix.append((char) codePoint);
            return true;
        });
        callback.cancel();
    }
}
