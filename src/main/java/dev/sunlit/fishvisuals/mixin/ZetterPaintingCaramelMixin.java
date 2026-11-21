package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.CaramelInputBridge;
import me.dantaeusb.zetter.client.gui.PaintingScreen;
import moe.caramel.chat.util.Rect;
import moe.caramel.chat.wrapper.AbstractIMEWrapper;
import net.minecraft.client.gui.font.TextFieldHelper;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = PaintingScreen.class, remap = false)
public abstract class ZetterPaintingCaramelMixin implements CaramelInputBridge.Field {
    @Shadow private String title;
    @Shadow private boolean editable;
    @Shadow @Final private TextFieldHelper titleEdit;
    @Shadow private int screenOffsetX;
    @Shadow private int paintingOffsetY;
    @Shadow private int paintingHeight;
    @Unique private CaramelInputBridge sunlit$ime;

    @Inject(method = "m_7856_", at = @At("RETURN"))
    private void sunlit$initIme(CallbackInfo ci) {
        if (sunlit$ime != null) sunlit$ime.setFocused(false);
        sunlit$ime = new CaramelInputBridge(this);
        sunlit$ime.setFocused(editable);
    }

    @Inject(method = "m_5534_", at = @At("HEAD"))
    private void sunlit$beforeEdit(CallbackInfoReturnable<Boolean> cir) {
        if (sunlit$ime != null && sunlit$ime.getStatus() != AbstractIMEWrapper.InputStatus.NONE) {
            sunlit$ime.setToNoneStatus();
        }
    }

    @Inject(method = {"m_7933_", "m_5534_"}, at = @At("RETURN"))
    private void sunlit$afterEdit(CallbackInfoReturnable<Boolean> cir) {
        if (sunlit$ime != null && sunlit$ime.getStatus() == AbstractIMEWrapper.InputStatus.NONE) sunlit$ime.setOrigin(title);
    }

    @Override public boolean editable() { return editable; }
    @Override public boolean full() { return sunlit$ime.probeReplacement().length() > 32; }
    @Override public String text() { return title; }
    @Override public void preview(String text) { title = text; }
    @Override public void insert(String text) { titleEdit.m_95158_(text); }
    @Override public int cursor() { return titleEdit.m_95194_(); }
    @Override public int selection() { return titleEdit.m_95197_(); }
    @Override public Rect rect() { return new Rect(screenOffsetX + 5, paintingOffsetY + paintingHeight + 5, 150, 12); }
}
