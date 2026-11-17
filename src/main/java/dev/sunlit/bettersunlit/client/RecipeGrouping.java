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

    /** Non-null keys are necessary match conditions. Null inputs/groups retain the full original comparison order. */
    public static <R, I, G, K> List<G> groupKeyed(List<R> recipes, Function<R, I> ingredient,
            Function<I, K> key, BiPredicate<I, I> matches, Function<I, G> create, BiConsumer<G, R> append) {
        record Candidate<I,G>(int index, I ingredient, G group) {}
        java.util.Map<K, List<Candidate<I,G>>> buckets = new java.util.HashMap<>();
        List<Candidate<I,G>> all = new ArrayList<>(), fallback = new ArrayList<>();
        List<G> groups = new ArrayList<>();
        for (R recipe : recipes) {
            I input = ingredient.apply(recipe);
            K signature = key.apply(input);
            var candidates = signature == null ? all : buckets.get(signature);
            G selected = null;
            int knownIndex = 0, fallbackIndex = 0;
            int knownSize = candidates == null ? 0 : candidates.size();
            int fallbackSize = signature == null ? 0 : fallback.size();
            while (knownIndex < knownSize || fallbackIndex < fallbackSize) {
                Candidate<I,G> candidate;
                if (fallbackIndex >= fallbackSize || knownIndex < knownSize && candidates.get(knownIndex).index() < fallback.get(fallbackIndex).index()) candidate = candidates.get(knownIndex++);
                else candidate = fallback.get(fallbackIndex++);
                if (matches.test(input, candidate.ingredient())) { selected = candidate.group(); break; }
            }
            if (selected == null) {
                selected = create.apply(input);
                append.accept(selected, recipe);
                var candidate = new Candidate<I,G>(groups.size(), input, selected);
                groups.add(selected); all.add(candidate);
                if (signature == null) fallback.add(candidate);
                else {
                    if (candidates == null) { candidates = new ArrayList<>(); buckets.put(signature, candidates); }
                    candidates.add(candidate);
                }
            } else append.accept(selected, recipe);
        }
        return groups;
    }
}
