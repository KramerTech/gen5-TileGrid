package com.tilepad;

/** Square-tile geometry: as many rows as fit at {@code minTile}, tiles sized to fill the height. */
final class GridMath {
    final int rows, cols, tile, gap, offsetX;

    private GridMath(int rows, int cols, int tile, int gap, int offsetX) {
        this.rows = rows; this.cols = cols; this.tile = tile; this.gap = gap; this.offsetX = offsetX;
    }

    static GridMath compute(int width, int height, int gap, int minTile, int maxTile) {
        width = Math.max(1, width); height = Math.max(1, height);
        int rows = Math.max(1, (height + gap) / (minTile + gap));
        int tile = Math.max(1, Math.min(maxTile, (height - gap * (rows - 1)) / rows));
        // Round rather than floor, then shrink tiles a little if needed: a window a few dp
        // narrower than six tiles should still get six columns, not five and a wide margin.
        int cols = Math.max(1, Math.round((width + gap) / (float) (tile + gap)));
        if (cols * tile + (cols - 1) * gap > width) tile = Math.max(1, (width - (cols - 1) * gap) / cols);
        int used = cols * tile + (cols - 1) * gap;
        return new GridMath(rows, cols, tile, gap, Math.max(0, (width - used) / 2));
    }

    int left(int index) { return offsetX + (index % cols) * (tile + gap); }
    int top(int index) { return (index / cols) * (tile + gap); }

    int contentHeight(int count) {
        int lines = Math.max(1, (count + cols - 1) / cols);
        return lines * tile + (lines - 1) * gap;
    }

    /** Slot nearest to a point (grid-local, e.g. a dragged tile's center), clamped to {@code count}. */
    int indexAt(float x, float y, int count) {
        if (count <= 0) return -1;
        int col = Math.round((x - offsetX - tile / 2f) / (tile + gap));
        int row = (int) Math.floor((y + gap / 2f) / (tile + gap));
        col = Math.max(0, Math.min(cols - 1, col));
        row = Math.max(0, row);
        return Math.min(count - 1, row * cols + col);
    }
}
