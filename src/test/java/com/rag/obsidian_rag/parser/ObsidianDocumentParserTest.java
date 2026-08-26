package com.rag.obsidian_rag.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObsidianDocumentParserTest {

    private final ObsidianDocumentParser parser = new ObsidianDocumentParser();

    @TempDir
    Path vault;

    @Test
    void parsesFrontmatterTagsAliasesAndLinks() throws Exception {
        String content = """
                ---
                title: My Note
                tags: [ml, rag]
                aliases: [My Alias, other]
                status: draft
                ---
                # Heading Should Not Be A Tag

                Body text with #inline/tag and a link to [[Other Note]].
                Also [[Second|displayed]] and [[Third#Section]] and [[Fourth#Sec|x]].
                """;

        Path file = vault.resolve("sub").resolve("My Note.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);

        ObsidianNote note = parser.parse(vault, file);

        assertEquals("My Note", note.noteTitle());
        assertEquals("sub/My Note.md", note.relativePath());
        assertFalse(note.body().contains("title:"));
        assertFalse(note.body().contains("---\ntitle"));

        assertTrue(note.tags().containsAll(List.of("ml", "rag", "inline/tag")));
        assertFalse(note.tags().stream().anyMatch(t -> t.equals("Heading")));

        assertEquals(List.of("My Alias", "other"), note.aliases());

        List<ObsidianNote.WikiLink> links = note.links();
        assertEquals(4, links.size());
        assertEquals("Other Note", links.get(0).target());
        assertEquals("Second", links.get(1).target());
        assertEquals("displayed", links.get(1).alias());
        assertEquals("Third", links.get(2).target());
        assertEquals("Section", links.get(2).anchor());
        assertEquals("x", links.get(3).alias());
    }

    @Test
    void ignoresTagsAndLinksInsideCodeFences() throws Exception {
        String content = """
                ---
                tags:
                  - front
                ---
                Real tag #real

                ```
                #faketag [[Fake Link]]
                ```
                """;

        Path file = vault.resolve("Code.md");
        Files.writeString(file, content);
        ObsidianNote note = parser.parse(vault, file);

        assertEquals(List.of("front", "real"), note.tags());
        assertTrue(note.links().isEmpty(), "links inside fences must be ignored");
    }

    @Test
    void computesStableContentHash() throws Exception {
        Path a = vault.resolve("A.md");
        Files.writeString(a, "hello");
        String h1 = parser.parse(vault, a).contentHash();

        Files.writeString(a, "hello");
        String h2 = parser.parse(vault, a).contentHash();
        Files.writeString(a, "world");
        String h3 = parser.parse(vault, a).contentHash();

        assertNotNull(h1);
        assertEquals(h1, h2);
        assertFalse(h1.equals(h3));
    }

    @Test
    void noteWithoutFrontmatterIsKeptAsIs() throws Exception {
        Path file = vault.resolve("Plain.md");
        Files.writeString(file, "just text\n");
        ObsidianNote note = parser.parse(vault, file);

        assertEquals("Plain", note.noteTitle());
        assertEquals("just text", note.body());
        assertTrue(note.frontmatter().isEmpty());
        assertTrue(note.tags().isEmpty());
    }
}
