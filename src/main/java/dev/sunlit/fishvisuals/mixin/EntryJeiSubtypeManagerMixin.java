package dev.sunlit.fishvisuals.mixin;
import dev.sunlit.bettersunlit.client.JeiPlainUid;
import mezz.jei.library.ingredients.subtypes.SubtypeInterpreters;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets="mezz.jei.library.ingredients.subtypes.SubtypeManager",remap=false)
public abstract class EntryJeiSubtypeManagerMixin implements JeiPlainUid.Manager {
    @Shadow @Final private SubtypeInterpreters interpreters;
    @Override public SubtypeInterpreters sunlit$interpreters() { return interpreters; }
}
