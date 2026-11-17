package dev.sunlit.bettersunlit.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** First-match grouping with per-invocation ingredient references; no cross-world cache. */
public final class RecipeGrouping {
    private RecipeGrouping() {}

    public static <R, I, G> List<G> group(List<R> recipes, Function<R, I> ingredient,
            BiPredicate<I, I> matches, Function<I, G> create, BiConsumer<G, R> append) {
        List<G> groups = new ArrayList<>();
        List<I> ingredients = new ArrayList<>();
        for (R recipe : recipes) {
            I input = ingredient.apply(recipe);
            int index = 0;
            while (index < groups.size() && !matches.test(input, ingredients.get(index))) {
                index++;
            }
            G group;
            if (index == groups.size()) {
                group = create.apply(input);
                // Append before publishing, matching Create's original exception/order behavior.
                append.accept(group, recipe);
                groups.add(group);
                ingredients.add(input);
            } else {
                group = groups.get(index);
                append.accept(group, recipe);
            }
        }
        return groups;
    }
}
