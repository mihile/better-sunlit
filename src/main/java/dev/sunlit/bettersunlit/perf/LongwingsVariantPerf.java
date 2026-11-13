package dev.sunlit.bettersunlit.perf;

import com.cicada.longwings.entity.ButterflyVariant;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Common allocation-free helpers for Longwings variant pool lookup and pattern matching. */
public final class LongwingsVariantPerf {
    private static final int MAX_PATTERN_CACHE = 2048;
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();
    private static final Object POOL_LOCK = new Object();
    private static volatile PoolSnapshot allPoolSnapshot;

    private LongwingsVariantPerf() {}

    private record PoolSnapshot(ButterflyVariant[] butterflies, ButterflyVariant[] moths,
                                ReadOnlyVariantList list) {}

    /**
     * Read-only ArrayList view reused across callers so per-frame render/model lookups
     * never allocate a fresh ArrayList while failing loudly if any external caller mutates it.
     */
    private static final class ReadOnlyVariantList extends ArrayList<ButterflyVariant> {
        private final ButterflyVariant[] backing;

        ReadOnlyVariantList(ButterflyVariant[] butterflies, ButterflyVariant[] moths) {
            super(0);
            int bLen = butterflies != null ? butterflies.length : 0;
            int mLen = moths != null ? moths.length : 0;
            this.backing = new ButterflyVariant[bLen + mLen];
            if (bLen > 0) System.arraycopy(butterflies, 0, this.backing, 0, bLen);
            if (mLen > 0) System.arraycopy(moths, 0, this.backing, bLen, mLen);
        }

        @Override public int size() { return backing.length; }
        @Override public boolean isEmpty() { return backing.length == 0; }
        @Override public ButterflyVariant get(int index) { return backing[index]; }

        @Override
        public int indexOf(Object o) {
            if (o == null) {
                for (int i = 0; i < backing.length; i++) if (backing[i] == null) return i;
            } else {
                for (int i = 0; i < backing.length; i++) if (o.equals(backing[i])) return i;
            }
            return -1;
        }

        @Override
        public int lastIndexOf(Object o) {
            if (o == null) {
                for (int i = backing.length - 1; i >= 0; i--) if (backing[i] == null) return i;
            } else {
                for (int i = backing.length - 1; i >= 0; i--) if (o.equals(backing[i])) return i;
            }
            return -1;
        }

        @Override public boolean contains(Object o) { return indexOf(o) >= 0; }
        @Override public Object[] toArray() { return backing.clone(); }
        @Override public Iterator<ButterflyVariant> iterator() { return listIterator(0); }
        @Override public ListIterator<ButterflyVariant> listIterator() { return listIterator(0); }

        @Override
        public ListIterator<ButterflyVariant> listIterator(int index) {
            if (index < 0 || index > backing.length) {
                throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + backing.length);
            }
            return new ReadOnlyIterator(backing, index);
        }

        @Override
        public void forEach(Consumer<? super ButterflyVariant> action) {
            Objects.requireNonNull(action);
            for (ButterflyVariant variant : backing) action.accept(variant);
        }

        @Override public List<ButterflyVariant> subList(int fromIndex, int toIndex) {
            if (fromIndex < 0 || toIndex > backing.length || fromIndex > toIndex) {
                throw new IndexOutOfBoundsException();
            }
            return new SubView(backing, fromIndex, toIndex);
        }

        @Override public Spliterator<ButterflyVariant> spliterator() {
            return java.util.Arrays.spliterator(backing);
        }

        @Override public ButterflyVariant set(int i, ButterflyVariant e) { throw new UnsupportedOperationException(); }
        @Override public boolean add(ButterflyVariant e) { throw new UnsupportedOperationException(); }
        @Override public void add(int i, ButterflyVariant e) { throw new UnsupportedOperationException(); }
        @Override public ButterflyVariant remove(int i) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public boolean addAll(Collection<? extends ButterflyVariant> c) { throw new UnsupportedOperationException(); }
        @Override public boolean addAll(int i, Collection<? extends ButterflyVariant> c) { throw new UnsupportedOperationException(); }
        @Override public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean removeIf(Predicate<? super ButterflyVariant> filter) { throw new UnsupportedOperationException(); }
        @Override public void replaceAll(UnaryOperator<ButterflyVariant> op) { throw new UnsupportedOperationException(); }
        @Override public void sort(Comparator<? super ButterflyVariant> c) { throw new UnsupportedOperationException(); }
        @Override public void ensureCapacity(int minCapacity) {}
        @Override public void trimToSize() {}
        @Override public Object clone() { return new ArrayList<>(this); }
    }

    private static final class SubView extends AbstractList<ButterflyVariant> implements java.util.RandomAccess {
        private final ButterflyVariant[] backing;
        private final int offset;
        private final int size;

        SubView(ButterflyVariant[] backing, int fromIndex, int toIndex) {
            this.backing = backing;
            this.offset = fromIndex;
            this.size = toIndex - fromIndex;
        }

        @Override public ButterflyVariant get(int index) {
            Objects.checkIndex(index, size);
            return backing[offset + index];
        }

        @Override public int size() { return size; }
    }

    private static final class ReadOnlyIterator implements ListIterator<ButterflyVariant> {
        private final ButterflyVariant[] backing;
        private int cursor;

        ReadOnlyIterator(ButterflyVariant[] backing, int cursor) {
            this.backing = backing;
            this.cursor = cursor;
        }

        @Override public boolean hasNext() { return cursor < backing.length; }
        @Override public ButterflyVariant next() {
            if (cursor >= backing.length) throw new NoSuchElementException();
            return backing[cursor++];
        }
        @Override public boolean hasPrevious() { return cursor > 0; }
        @Override public ButterflyVariant previous() {
            if (cursor <= 0) throw new NoSuchElementException();
            return backing[--cursor];
        }
        @Override public int nextIndex() { return cursor; }
        @Override public int previousIndex() { return cursor - 1; }
        @Override public void remove() { throw new UnsupportedOperationException(); }
        @Override public void set(ButterflyVariant e) { throw new UnsupportedOperationException(); }
        @Override public void add(ButterflyVariant e) { throw new UnsupportedOperationException(); }
    }

    /** Returns the cached combined butterfly+moth variant pool, rebuilding only if source arrays change. */
    public static ArrayList<ButterflyVariant> cachedAllVariantPool(
            ButterflyVariant[] butterflies, ButterflyVariant[] moths) {
        PoolSnapshot snap = allPoolSnapshot;
        if (snap != null && snap.butterflies == butterflies && snap.moths == moths) {
            return snap.list;
        }
        synchronized (POOL_LOCK) {
            snap = allPoolSnapshot;
            if (snap != null && snap.butterflies == butterflies && snap.moths == moths) {
                return snap.list;
            }
            ReadOnlyVariantList list = new ReadOnlyVariantList(butterflies, moths);
            allPoolSnapshot = new PoolSnapshot(butterflies, moths, list);
            return list;
        }
    }

    /** Fast equivalent of {@link String#matches(String)} without compiling Patterns for literal IDs. */
    public static boolean matches(String candidate, String pattern) {
        if (candidate == null || pattern == null) {
            return false;
        }
        if (!hasRegexMeta(pattern)) {
            return candidate.equals(pattern);
        }
        Pattern compiled = PATTERN_CACHE.get(pattern);
        if (compiled == null) {
            compiled = Pattern.compile(pattern);
            if (PATTERN_CACHE.size() < MAX_PATTERN_CACHE) {
                Pattern prev = PATTERN_CACHE.putIfAbsent(pattern, compiled);
                if (prev != null) compiled = prev;
            }
        }
        return compiled.matcher(candidate).matches();
    }

    public static boolean hasRegexMeta(String s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            switch (s.charAt(i)) {
                case '\\', '.', '[', ']', '{', '}', '(', ')', '*', '+', '?', '^', '$', '|' -> {
                    return true;
                }
                default -> {}
            }
        }
        return false;
    }

    public static void clearCachesForTests() {
        PATTERN_CACHE.clear();
        allPoolSnapshot = null;
    }
}
