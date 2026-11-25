package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.CaramelInputBridge;
import moe.caramel.chat.util.Rect;
import moe.caramel.chat.wrapper.AbstractIMEWrapper;
import net.mehvahdjukaar.supplementaries.client.screens.widgets.MultiLineEditBoxWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.font.TextFieldHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MultiLineEditBoxWidget.class, remap = false)
public abstract class SupplementariesTextBoxCaramelMixin implements CaramelInputBridge.Field {
    @Shadow @Final protected TextFieldHelper pageEdit;
    @Shadow public abstract String getText();
    @Shadow public abstract void setText(String text);
    @Shadow public abstract boolean canConsumeInput();
    @Shadow private boolean isStringValid(String text) { throw new AssertionError(); }
    @Unique private CaramelInputBridge sunlit$ime;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sunlit$initIme(CallbackInfo ci) { sunlit$ime = new CaramelInputBridge(this); }
    @Inject(method = "tick", at = @At("RETURN"))
    private void sunlit$focusIme(CallbackInfo ci) {
        boolean focused = canConsumeInput();
        if (focused && !sunlit$ime.getIme().isFocused()) sunlit$ime.setOrigin(text());
        sunlit$ime.setFocused(focused);
    }
    @Inject(method = {"m_5534_", "m_93680_"}, at = @At("HEAD"))
    private void sunlit$beforeEdit(CallbackInfoReturnable<Boolean> ci) {
        if (sunlit$ime.getStatus() != AbstractIMEWrapper.InputStatus.NONE) sunlit$ime.setToNoneStatus();
    }
    @Inject(method = {"m_7933_", "m_5534_", "m_93680_"}, at = @At("RETURN"))
    private void sunlit$afterEdit(CallbackInfoReturnable<Boolean> ci) {
        if (sunlit$ime.getStatus() == AbstractIMEWrapper.InputStatus.NONE) sunlit$ime.setOrigin(text());
    }
    @Override public boolean editable() { return canConsumeInput(); }
    @Override public boolean full() { return !isStringValid(sunlit$ime.probeReplacement()); }
    @Override public String text() { return getText(); }
    @Override public void preview(String text) { setText(text); }
    @Override public void insert(String text) { pageEdit.m_95158_(text); }
    @Override public int cursor() { return pageEdit.m_95194_(); }
    @Override public int selection() { return pageEdit.m_95197_(); }
    @Override public Rect rect() {
        AbstractWidget self = (AbstractWidget) (Object) this;
        return new Rect(self.m_252754_(), self.m_252907_(), self.m_5711_(), self.m_93694_());
    }
}
