package dev.sunlit.fishvisuals.mixin;

import org.embeddedt.embeddium.gui.frame.components.SearchTextFieldModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SearchTextFieldModel.class, remap = false)
public interface EmbeddiumSearchModelAccessor {
    @Accessor("text") String sunlit$getText();
    @Accessor("text") void sunlit$setText(String value);
    @Accessor("selectionStart") int sunlit$getStart();
    @Accessor("selectionEnd") int sunlit$getEnd();
    @Accessor("maxLength") int sunlit$getMaxLength();
    @Accessor("firstCharacterIndex") int sunlit$getFirstCharacter();
    @Accessor("firstCharacterIndex") void sunlit$setFirstCharacter(int value);
}
