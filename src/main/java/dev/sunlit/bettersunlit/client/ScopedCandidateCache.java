package dev.sunlit.bettersunlit.client;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** A cache exists only during one synchronous ingredient-list build on this thread. */
public final class ScopedCandidateCache {
    private static final ThreadLocal<IdentityHashMap<Object,List<?>>> ACTIVE=new ThreadLocal<>();
    private ScopedCandidateCache() {}

    public static Scope open() {
        var previous=ACTIVE.get();ACTIVE.set(new IdentityHashMap<>());return new Scope(previous);
    }
    public static final class Scope implements AutoCloseable {
        private final IdentityHashMap<Object,List<?>> previous;
        private boolean closed;
        private Scope(IdentityHashMap<Object,List<?>> previous){this.previous=previous;}
        @Override public void close(){if(!closed){closed=true;if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}}
    }
    @SuppressWarnings("unchecked")
    public static <T> Collection<T> candidates(Object registry,Supplier<Collection<T>> values,Predicate<T> allowed) {
        var active=ACTIVE.get();
        if(active==null)return values.get();
        var cached=active.get(registry);
        if(cached!=null)return (List<T>)cached;
        List<T> result=new ArrayList<>();for(T value:values.get())if(allowed.test(value))result.add(value);
        result=List.copyOf(result);active.put(registry,result);return result;
    }
}
