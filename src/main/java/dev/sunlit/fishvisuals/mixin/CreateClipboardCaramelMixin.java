package dev.sunlit.fishvisuals.mixin;

import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import dev.sunlit.bettersunlit.client.CaramelInputBridge;
import moe.caramel.chat.util.Rect;
import moe.caramel.chat.wrapper.AbstractIMEWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = ClipboardScreen.class, remap = false)
public abstract class CreateClipboardCaramelMixin implements CaramelInputBridge.Field {
    @Shadow private List<ClipboardEntry> currentEntries;
    @Shadow private int editingIndex;
    @Shadow private boolean readonly;
    @Shadow private TextFieldHelper editContext;
    @Shadow private int currentPage;
    @Shadow private void clearDisplayCache() { throw new AssertionError(); }
    @Shadow private boolean validateTextForEntry(String text) { throw new AssertionError(); }

    @Unique private CaramelInputBridge sunlit$ime;
    @Unique private int sunlit$entry = -1;
    @Unique private int sunlit$page = -1;

    @Inject(method = "m_7856_", at = @At("RETURN"))
    private void sunlit$initIme(CallbackInfo ci) {
        if (sunlit$ime == null) sunlit$ime = new CaramelInputBridge(this);
        sunlit$syncIme();
    }

    @Inject(method = "m_86600_", at = @At("RETURN"))
    private void sunlit$tickIme(CallbackInfo ci) {
        sunlit$syncIme();
    }

    @Inject(method = {"m_5534_", "m_6375_", "m_7979_", "m_6050_"},
            at = @At("HEAD"))
    private void sunlit$beforeInteraction(CallbackInfoReturnable<Boolean> cir) {
        if (sunlit$ime != null && sunlit$ime.getStatus() != AbstractIMEWrapper.InputStatus.NONE) {
            sunlit$ime.setToNoneStatus();
        }
    }

    @Inject(method = {"m_7933_", "m_5534_", "m_6375_", "m_7979_", "m_6050_"},
            at = @At("RETURN"))
    private void sunlit$afterInteraction(CallbackInfoReturnable<Boolean> cir) {
        sunlit$syncIme();
        if (sunlit$ime != null && editable() && sunlit$ime.getStatus() == AbstractIMEWrapper.InputStatus.NONE) {
            sunlit$ime.setOrigin(text());
        }
    }

    @Unique
    private void sunlit$syncIme() {
        if (sunlit$ime == null) return;
        boolean active = editable();
        if (sunlit$entry != editingIndex || sunlit$page != currentPage) {
            sunlit$ime.setFocused(false);
            sunlit$entry = editingIndex;
            sunlit$page = currentPage;
            if (active) sunlit$ime.setOrigin(text());
        }
        sunlit$ime.setFocused(active);
    }

    @Override public boolean editable() {
        return !readonly && currentEntries != null && editingIndex >= 0 && editingIndex < currentEntries.size();
    }
    @Override public boolean full() { return !editable() || !validateTextForEntry(sunlit$ime.probeReplacement()); }
    @Override public String text() { return editable() ? currentEntries.get(editingIndex).text.getString() : ""; }
    @Override public void preview(String text) {
        if (!editable()) return;
        currentEntries.get(editingIndex).text = Component.m_237113_(text);
        clearDisplayCache();
    }
    @Override public void insert(String text) { editContext.m_95158_(text); clearDisplayCache(); }
    @Override public int cursor() { return editContext.m_95194_(); }
    @Override public int selection() { return editContext.m_95197_(); }
    @Override public Rect rect() {
        var window = Minecraft.m_91087_().m_91268_();
        return new Rect((window.m_85445_() - 256) / 2f + 50, (window.m_85446_() - 256) / 2f + 45, 150, 12);
    }
}
