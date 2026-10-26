package com.tilepad;

import java.util.Locale;

/** Every whitespace-separated query word must appear in the label or the component name. */
final class SearchFilter {
    static boolean matches(String query, String label, String component) {
        if (query == null) return true;
        String haystack = ((label == null ? "" : label) + " " + (component == null ? "" : component))
            .toLowerCase(Locale.US);
        for (String word : query.toLowerCase(Locale.US).trim().split("\\s+")) {
            if (!word.isEmpty() && !haystack.contains(word)) return false;
        }
        return true;
    }

    /** 0: label starts with the query, 1: every word is in the label, 2: matched via the component only. */
    static int rank(String query, String label) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
        String l = label == null ? "" : label.toLowerCase(Locale.US);
        if (q.isEmpty() || l.startsWith(q)) return 0;
        return matches(q, l, null) ? 1 : 2;
    }

    private SearchFilter() { }
}
