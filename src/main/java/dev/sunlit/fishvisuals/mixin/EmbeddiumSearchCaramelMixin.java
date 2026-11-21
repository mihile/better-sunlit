package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.CaramelInputBridge;
import me.jellysquid.mods.sodium.client.util.Dim2i;
import moe.caramel.chat.util.Rect;
import moe.caramel.chat.wrapper.AbstractIMEWrapper;
import org.embeddedt.embeddium.gui.frame.components.SearchTextFieldComponent;
import org.embeddedt.embeddium.gui.frame.components.SearchTextFieldModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SearchTextFieldComponent.class, remap = false)
public abstract class EmbeddiumSearchCaramelMixin implements CaramelInputBridge.Field {
    @Shadow @Final private SearchTextFieldModel model;
    @Shadow @Final protected Dim2i dim;
    @Shadow public abstract boolean m_93696_();
    @Shadow public abstract boolean isEditable();
    @Shadow public abstract boolean isVisible();
    @Unique private CaramelInputBridge sunlit$ime;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sunlit$initIme(CallbackInfo ci) { sunlit$ime = new CaramelInputBridge(this); }

    @Inject(method = "m_93692_", at = @At("RETURN"))
    private void sunlit$focusIme(boolean focused, CallbackInfo ci) {
        if (sunlit$ime == null) return;
        if (focused) sunlit$ime.setOrigin(text());
        sunlit$ime.setFocused(focused && editable());
    }

    @Inject(method = {"m_5534_", "m_6375_"}, at = @At("HEAD"))
    private void sunlit$beforeInteraction(CallbackInfoReturnable<Boolean> cir) {
        if (sunlit$ime != null && sunlit$ime.getStatus() != AbstractIMEWrapper.InputStatus.NONE) {
            sunlit$ime.setToNoneStatus();
        }
    }

    @Inject(method = {"m_7933_", "m_5534_", "m_6375_"}, at = @At("RETURN"))
    private void sunlit$afterInteraction(CallbackInfoReturnable<Boolean> cir) {
        if (sunlit$ime != null && sunlit$ime.getStatus() == AbstractIMEWrapper.InputStatus.NONE) sunlit$ime.setOrigin(text());
    }

    @Unique private EmbeddiumSearchModelAccessor sunlit$model() {
        return (EmbeddiumSearchModelAccessor) model;
    }
    @Override public boolean editable() { return m_93696_() && isVisible() && isEditable(); }
    @Override public boolean full() {
        return sunlit$ime.probeReplacement().length() > sunlit$model().sunlit$getMaxLength();
    }
    @Override public String text() { return sunlit$model().sunlit$getText(); }
    @Override public void preview(String text) {
        var access = sunlit$model();
        access.sunlit$setText(text);
        access.sunlit$setFirstCharacter(Math.min(access.sunlit$getFirstCharacter(), text.length()));
    }
    @Override public void insert(String text) { model.write(text); }
    @Override public void removeSelectionForPreview() {
        int from = Math.min(cursor(), selection());
        int to = Math.max(cursor(), selection());
        preview(text().substring(0, from) + text().substring(to));
        model.setSelectionStart(from);
        model.setSelectionEnd(from);
    }
    @Override public int cursor() { return sunlit$model().sunlit$getStart(); }
    @Override public int selection() { return sunlit$model().sunlit$getEnd(); }
    @Override public Rect rect() { return new Rect(dim.x() + 6, dim.y() + 6, dim.width() - 12, dim.height()); }
}
