package com.codefromheaven.mcp.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TagUtilsTest {

    @Test
    void extractTagsFromPrefix_shouldSplitByVariousDelimiters() {
        String prefix = "DD130, DD131.DD132_DD133-DD134   DD135";
        Set<String> tags = TagUtils.extractTagsFromPrefix(prefix);

        assertEquals(6, tags.size());
        assertTrue(tags.contains("DD130"));
        assertTrue(tags.contains("DD131"));
        assertTrue(tags.contains("DD132"));
        assertTrue(tags.contains("DD133"));
        assertTrue(tags.contains("DD134"));
        assertTrue(tags.contains("DD135"));
    }

    @Test
    void extractTagsFromPrefix_shouldIgnoreEmptyOrShortTokens() {
        String prefix = "A, B, CD, E, FGH";
        Set<String> tags = TagUtils.extractTagsFromPrefix(prefix);

        assertEquals(2, tags.size());
        assertTrue(tags.contains("CD"));
        assertTrue(tags.contains("FGH"));
        assertFalse(tags.contains("A"));
    }

    @Test
    void extractTagsFromPrefix_shouldHandleNullOrBlank() {
        assertTrue(TagUtils.extractTagsFromPrefix(null).isEmpty());
        assertTrue(TagUtils.extractTagsFromPrefix("").isEmpty());
        assertTrue(TagUtils.extractTagsFromPrefix("   , . - _  ").isEmpty());
    }

    @Test
    void getDynamicTagsForFile_shouldExtractTagsOnlyIfPathMatchesPrefix() {
        List<String> prefixes = List.of("DD130_Deliverables", "Architecture-Docs");

        // File matches first prefix
        Set<String> tags1 = TagUtils.getDynamicTagsForFile(prefixes, "/app/data/DD130_Deliverables/doc1.md");
        assertEquals(2, tags1.size());
        assertTrue(tags1.contains("DD130"));
        assertTrue(tags1.contains("Deliverables"));

        // File matches second prefix
        Set<String> tags2 = TagUtils.getDynamicTagsForFile(prefixes, "/app/data/Architecture-Docs/diagram.md");
        assertEquals(2, tags2.size());
        assertTrue(tags2.contains("Architecture"));
        assertTrue(tags2.contains("Docs"));

        // File matches no prefix
        Set<String> tags3 = TagUtils.getDynamicTagsForFile(prefixes, "/app/data/Other/file.md");
        assertTrue(tags3.isEmpty());

        // File matches prefix but with different casing
        Set<String> tags4 = TagUtils.getDynamicTagsForFile(List.of("o0500"), "/app/data/O0500-Software-Architecture/doc.md");
        assertEquals(1, tags4.size());
        assertTrue(tags4.contains("o0500"));
    }

    @Test
    void getDynamicTagsForFile_shouldHandleNulls() {
        assertTrue(TagUtils.getDynamicTagsForFile(null, "/path").isEmpty());
        assertTrue(TagUtils.getDynamicTagsForFile(List.of("prefix"), null).isEmpty());
    }
}
