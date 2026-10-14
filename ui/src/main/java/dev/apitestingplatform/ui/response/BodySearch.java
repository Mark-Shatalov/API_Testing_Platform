package dev.apitestingplatform.ui.response;

/** Searches the entire captured text without building match lists or lowercase copies. */
public final class BodySearch {
    private BodySearch() { }
    public static int find(String text, String query, int start, boolean backwards) {
        if (query.isEmpty()) return -1;
        if (backwards) {
            int found = text.lastIndexOf(query, Math.min(start, text.length()));
            return found >= 0 ? found : text.lastIndexOf(query);
        }
        int found = text.indexOf(query, Math.max(0, start));
        return found >= 0 ? found : text.indexOf(query);
    }
}
