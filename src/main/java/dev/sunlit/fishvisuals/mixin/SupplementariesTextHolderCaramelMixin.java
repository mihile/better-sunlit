package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.CaramelInputBridge;
import moe.caramel.chat.util.Rect;
import moe.caramel.chat.wrapper.AbstractIMEWrapper;
import net.mehvahdjukaar.supplementaries.client.screens.TextHolderEditScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.TextFieldHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TextHolderEditScreen.class, remap = false)
public abstract class SupplementariesTextHolderCaramelMixin implements CaramelInputBridge.Field {
    @Shadow @Final protected String[][] messages;
    @Shadow protected TextFieldHelper textInputUtil;
    @Shadow protected int lineIndex;
    @Shadow protected int textHolderIndex;
    @Unique private CaramelInputBridge sunlit$ime;

    @Inject(method = "m_7856_", at = @At("RETURN"))
    private void sunlit$initIme(CallbackInfo ci) {
        if (sunlit$ime != null) sunlit$ime.setFocused(false);
        sunlit$ime = new CaramelInputBridge(this);
        sunlit$ime.setFocused(true);
    }
    @Inject(method = {"m_5534_", "m_6050_"}, at = @At("HEAD"))
    private void sunlit$beforeEdit(CallbackInfoReturnable<Boolean> ci) {
        if (sunlit$ime != null && sunlit$ime.getStatus() != AbstractIMEWrapper.InputStatus.NONE) {
            sunlit$ime.setToNoneStatus();
        }
    }
    @Inject(method = {"m_7933_", "m_5534_", "m_6050_"}, at = @At("RETURN"))
    private void sunlit$afterEdit(CallbackInfoReturnable<Boolean> ci) {
        if (sunlit$ime != null && sunlit$ime.getStatus() == AbstractIMEWrapper.InputStatus.NONE) sunlit$ime.setOrigin(text());
    }
    @Override public boolean editable() { return textInputUtil != null; }
    @Override public boolean full() { return false; }
    @Override public String text() { return messages[textHolderIndex][lineIndex]; }
    @Override public void preview(String text) { messages[textHolderIndex][lineIndex] = text; }
    @Override public void insert(String text) { textInputUtil.m_95158_(text); }
    @Override public int cursor() { return textInputUtil.m_95194_(); }
    @Override public int selection() { return textInputUtil.m_95197_(); }
    @Override public Rect rect() {
        var window = Minecraft.m_91087_().m_91268_();
        return new Rect(window.m_85445_() / 2f - 100, window.m_85446_() / 2f, 200, 12);
    }
}
