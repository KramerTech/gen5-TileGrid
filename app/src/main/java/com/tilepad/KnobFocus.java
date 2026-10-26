package com.tilepad;

/** Pure selection policy shared by view controls and the scrolling app catalog. */
final class KnobFocus {
    static int next(int current, int delta, boolean[] enabled) {
        int size = enabled.length;
        if (size == 0) return -1;
        int index = current < 0 ? (delta > 0 ? -1 : 0) : current;
        for (int i = 0; i < size; i++) {
            index = ((index + delta) % size + size) % size;
            if (enabled[index]) return index;
        }
        return -1;
    }
}
