package shortestpath.pathfinder;

/**
 * Sparse {@code int -> int} counter map used for the tile-visit heatmap.
 * Open addressing with linear probing over two parallel primitive arrays —
 * roughly 8 bytes per live entry versus ~64 for {@code HashMap<Integer,int[]>},
 * and no node or lambda allocation per increment in the search hot loop.
 * Keys must be non-negative (packed world positions are); {@code -1} is the
 * empty-slot sentinel.
 */
public final class IntCounterMap {

    private static final int EMPTY = -1;
    private static final float LOAD_FACTOR = 0.7f;

    private int[] keys;
    private int[] counts;
    private int size;
    private int mask;

    public IntCounterMap() {
        this(64);
    }

    public IntCounterMap(int expectedEntries) {
        int capacity = 16;
        while (capacity < expectedEntries / LOAD_FACTOR) {
            capacity <<= 1;
        }
        keys = new int[capacity];
        counts = new int[capacity];
        mask = capacity - 1;
        java.util.Arrays.fill(keys, EMPTY);
    }

    /** Increment the count for {@code key}, inserting it at 1 when absent. */
    public void increment(int key) {
        int slot = findSlot(key);
        if (keys[slot] == EMPTY) {
            keys[slot] = key;
            counts[slot] = 1;
            if (++size >= (int) (keys.length * LOAD_FACTOR)) {
                rehash();
            }
        } else {
            counts[slot]++;
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Visit every (key, count) pair in table order — no defined ordering. */
    public void forEach(EntryConsumer consumer) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) {
                consumer.accept(keys[i], counts[i]);
            }
        }
    }

    @FunctionalInterface
    public interface EntryConsumer {
        void accept(int key, int count);
    }

    private int findSlot(int key) {
        int slot = mix(key) & mask;
        while (keys[slot] != EMPTY && keys[slot] != key) {
            slot = (slot + 1) & mask;
        }
        return slot;
    }

    // Packed positions are structured (plane bits in the high end), so spread
    // them with a multiplicative mix before masking.
    private static int mix(int key) {
        return key * 0x9E3779B1;
    }

    private void rehash() {
        int[] oldKeys = keys;
        int[] oldCounts = counts;
        keys = new int[oldKeys.length << 1];
        counts = new int[keys.length];
        mask = keys.length - 1;
        java.util.Arrays.fill(keys, EMPTY);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                int slot = findSlot(oldKeys[i]);
                keys[slot] = oldKeys[i];
                counts[slot] = oldCounts[i];
            }
        }
    }
}
