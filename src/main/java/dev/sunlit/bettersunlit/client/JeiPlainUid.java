package dev.sunlit.bettersunlit.client;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.ISubtypeManager;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.library.ingredients.subtypes.SubtypeInterpreters;
import mezz.jei.library.ingredients.subtypes.SubtypeManager;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper;

/** The reviewed RecipeMap's plain-item UID is its registered Item, independent of a temporary ItemStack. */
public final class JeiPlainUid {
    public interface PlainItem { Object sunlit$plainItem(); }
    public interface Helper { ISubtypeManager sunlit$subtypeManager(); }
    public interface Manager { SubtypeInterpreters sunlit$interpreters(); }
    public interface Interpreters { boolean sunlit$containsBase(Object item); }
    private JeiPlainUid() {}

    /** Null means use the entire original implementation; no result or subtype registry is cached. */
    public static Object tryUid(ITypedIngredient<?> ingredient, IIngredientManager manager) {
        if (!(ingredient instanceof PlainItem item) || ingredient.getType() != VanillaTypes.ITEM_STACK) return null;
        Object helper = manager.getIngredientHelper(VanillaTypes.ITEM_STACK);
        if (helper.getClass() != ItemStackHelper.class || !(helper instanceof Helper access)) return null;
        ISubtypeManager subtypes = access.sunlit$subtypeManager();
        if (subtypes == null || subtypes.getClass() != SubtypeManager.class || !(subtypes instanceof Manager subtypeAccess)) return null;
        SubtypeInterpreters interpreters = subtypeAccess.sunlit$interpreters();
        if (interpreters == null || interpreters.getClass() != SubtypeInterpreters.class || !(interpreters instanceof Interpreters registry)) return null;
        Object base = item.sunlit$plainItem();
        return base != null && !registry.sunlit$containsBase(base) ? base : null;
    }
}
