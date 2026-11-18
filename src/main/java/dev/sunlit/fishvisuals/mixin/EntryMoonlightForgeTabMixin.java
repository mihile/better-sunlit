package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ForgeCreativeTabScope;
import net.mehvahdjukaar.moonlight.api.platform.RegHelper.ItemToTabEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Consumer;

@Pseudo
@Mixin(targets = "net.mehvahdjukaar.moonlight.api.platform.forge.RegHelperImpl", remap = false)
public abstract class EntryMoonlightForgeTabMixin {
    @Redirect(method = "lambda$addItemsToTabsRegistration$18",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"),
            require = 1)
    private static void sunlit$markForgeTab(Consumer<ItemToTabEvent> listener, Object argument,
                                            Consumer<ItemToTabEvent> originalListener,
                                            BuildCreativeModeTabContentsEvent event) {
        ForgeCreativeTabScope.accept(listener, (ItemToTabEvent) argument, event.getTabKey());
    }
}
