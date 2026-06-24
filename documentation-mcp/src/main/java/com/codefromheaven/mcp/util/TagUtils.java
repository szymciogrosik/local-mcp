package com.codefromheaven.mcp.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public final class TagUtils {

    private TagUtils() {
        // Utility class
    }

    /**
     * Extracts tags from a directory prefix by splitting on spaces, commas, dots, dashes, and underscores.
     * @param prefix the directory prefix to split
     * @return a set of cleaned tags
     */
    public static Set<String> extractTagsFromPrefix(String prefix) {
        Set<String> extractedTags = new HashSet<>();
        if (prefix == null || prefix.isBlank()) {
            return extractedTags;
        }

        // Split on one or more punctuation marks or spaces: comma, space, dot, dash, underscore
        String[] tokens = prefix.split("[,\\.\\-_\\s]+");
        for (String token : tokens) {
            String cleanedToken = token.trim();
            if (cleanedToken.length() > 1) { // ignore empty or 1-character tokens
                extractedTags.add(cleanedToken);
            }
        }
        return extractedTags;
    }

    /**
     * Extracts dynamic tags for a given file based on the configured directory prefixes.
     * @param directoryPrefixes the prefixes configured for the source
     * @param filePath the path of the file to check
     * @return a set of dynamic tags derived from matching prefixes
     */
    public static Set<String> getDynamicTagsForFile(Collection<String> directoryPrefixes, String filePath) {
        Set<String> tags = new HashSet<>();
        if (directoryPrefixes == null || directoryPrefixes.isEmpty() || filePath == null) {
            return tags;
        }

        String filePathLower = filePath.toLowerCase();
        for (String prefix : directoryPrefixes) {
            if (prefix != null && filePathLower.contains(prefix.toLowerCase())) {
                tags.addAll(extractTagsFromPrefix(prefix));
            }
        }
        return tags;
    }
}
