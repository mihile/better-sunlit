package dev.sunlit.fishvisuals.mixin;
import dev.sunlit.bettersunlit.client.JeiPlainUid;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets="mezz.jei.library.ingredients.itemStacks.NormalizedTypedItem",remap=false)
public abstract class EntryJeiPlainItemMixin implements JeiPlainUid.PlainItem {
    @Shadow @Final private Holder<Item> itemHolder;
    @Override public Object sunlit$plainItem() { return itemHolder.m_203334_(); }
}
