package com.rag.obsidian_rag.parser;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownSectionSplitterTest {

    private ObsidianNote note(String body) {
        return new ObsidianNote("My Note", "notes/My Note.md", body, "abc123",
                Map.of(), List.of("ML", "Rag"), List.of(), List.of());
    }

    @Test
    void propagatesHeadingPathsAndMetadata() {
        String body = """
                Intro paragraph.

                ## Setup
                Setup text here.

                ### Details
                Details text.
                """;

        List<TextSegment> segments = MarkdownSectionSplitter.split(note(body), 8000, 100);

        assertFalse(segments.isEmpty());

        TextSegment intro = segments.get(0);
        assertEquals(0, intro.metadata().getInteger("chunk_index"));
        assertEquals("My Note", intro.metadata().getString("note_title"));
        assertEquals("abc123", intro.metadata().getString("content_hash"));
        assertEquals("ml,rag", intro.metadata().getString("tags_csv"));
        assertTrue(intro.text().startsWith("[My Note]"));

        boolean hasSetup = segments.stream().anyMatch(s ->
                "Setup".equals(s.metadata().getString("heading_path"))
                        && s.text().startsWith("[My Note › Setup]"));
        assertTrue(hasSetup);

        boolean hasDetails = segments.stream().anyMatch(s ->
                "Setup › Details".equals(s.metadata().getString("heading_path")));
        assertTrue(hasDetails);

        for (int i = 0; i < segments.size(); i++) {
            assertEquals(i, segments.get(i).metadata().getInteger("chunk_index"));
        }
    }

    @Test
    void splitsLongSectionsIntoMultipleChunks() {
        StringBuilder body = new StringBuilder("## Big\n");
        // ~3000 chars of repeated words -> exceeds chunkSize 500 -> multiple chunks
        for (int i = 0; i < 400; i++) {
            body.append("word").append(i).append(" filler text to grow the section. ");
        }
        List<TextSegment> segments = MarkdownSectionSplitter.split(note(body.toString()), 500, 50);

        assertTrue(segments.size() > 1);
        assertTrue(segments.stream().allMatch(s -> s.text().startsWith("[My Note › Big]")));
    }

    @Test
    void codeFenceHeadingsDoNotSplitSections() {
        String body = """
                ## Real
                ```
                ## Not A Heading
                ```
                still real section
                """;
        List<TextSegment> segments = MarkdownSectionSplitter.split(note(body), 8000, 0);

        assertEquals(1, segments.size());
        assertEquals("Real", segments.get(0).metadata().getString("heading_path"));
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}
