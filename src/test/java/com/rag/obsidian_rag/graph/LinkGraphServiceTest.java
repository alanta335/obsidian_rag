package com.rag.obsidian_rag.graph;

import com.rag.obsidian_rag.parser.ObsidianNote;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkGraphServiceTest {

    /**
     * Creates a note stub with outgoing wikilinks for graph tests.
     *
     * @param title       note title
     * @param path        vault-relative path
     * @param aliases     frontmatter aliases
     * @param linkTargets plain wikilink targets (no anchors or aliases)
     * @return parsed-note stub
     */
    private ObsidianNote note(String title, String path, List<String> aliases, String... linkTargets) {
        List<ObsidianNote.WikiLink> links = java.util.Arrays.stream(linkTargets)
                .map(t -> new ObsidianNote.WikiLink(t, null, null))
                .toList();
        return new ObsidianNote(title, path, "", "h", Map.of(), List.of(), aliases, links);
    }

    /**
     * Creates a note stub with explicitly typed wikilinks for weighted-scoring tests.
     *
     * @param title note title
     * @param path  vault-relative path
     * @param links typed wikilinks
     * @return parsed-note stub
     */
    private ObsidianNote noteWithLinks(String title, String path, ObsidianNote.WikiLink... links) {
        return new ObsidianNote(title, path, "", "h", Map.of(), List.of(), List.of(), List.of(links));
    }

    @Test
    void resolvesTitlesFoldersAndAliases() {
        LinkGraphService graph = new LinkGraphService();
        graph.rebuild(List.of(
                note("Zettelkasten", "methods/zk.md", List.of("ZK")),
                note("Daily", "daily/2024-01-01.md", List.of()),
                note("Rag", "concepts/rag.md", List.of())
        ));

        assertEquals("methods/zk.md", graph.resolvePath("zettelkasten"));
        assertEquals("daily/2024-01-01.md", graph.resolvePath("Daily"));
        assertEquals("methods/zk.md", graph.resolvePath("ZK"));
        assertNull(graph.resolvePath("Unknown"));
    }

    @Test
    void backlinksPointFromSourceToTarget() {
        LinkGraphService graph = new LinkGraphService();
        graph.rebuild(List.of(
                note("A", "a.md", List.of(), "B"),
                note("B", "b.md", List.of(), "A"),
                note("C", "c.md", List.of(), "B")
        ));

        var backlinksOfB = graph.backlinkTitles("B");
        assertTrue(backlinksOfB.contains("a"));
        assertTrue(backlinksOfB.contains("c"));
        assertEquals(2, backlinksOfB.size());
    }

    @Test
    void expansionCandidatesRankByConnectionsAndExcludeHits() {
        LinkGraphService graph = new LinkGraphService();
        graph.rebuild(List.of(
                note("Hub", "hub.md", List.of(), "A", "B"),
                note("A", "a.md", List.of(), "Hub"),
                note("B", "b.md", List.of())
        ));

        // Query hits Hub: A connects twice (outgoing of hub + backlink source), B once
        List<String> candidates = graph.expansionCandidates(List.of("Hub"), 5);
        assertEquals(List.of("A", "B"), candidates);

        // limit respected
        List<String> limited = graph.expansionCandidates(List.of("Hub"), 1);
        assertEquals(List.of("A"), limited);
    }

    @Test
    void expansionCandidatesWeightByLinkType() {
        LinkGraphService graph = new LinkGraphService();
        graph.rebuild(List.of(
                noteWithLinks("Hub", "hub.md",
                        new ObsidianNote.WikiLink("A", null, null, ObsidianNote.LinkType.BODY),
                        new ObsidianNote.WikiLink("B", null, null, ObsidianNote.LinkType.FOOTER),
                        new ObsidianNote.WikiLink("C", null, null, ObsidianNote.LinkType.FRONTMATTER)),
                noteWithLinks("A", "a.md",
                        new ObsidianNote.WikiLink("Hub", null, null, ObsidianNote.LinkType.BODY)),
                noteWithLinks("B", "b.md",
                        new ObsidianNote.WikiLink("Hub", null, null, ObsidianNote.LinkType.FOOTER)),
                noteWithLinks("C", "c.md",
                        new ObsidianNote.WikiLink("Hub", null, null, ObsidianNote.LinkType.FRONTMATTER))
        ));

        // A: 1.0 (out) + 1.0 (backlink) = 2.0; B: 0.5 + 0.5 = 1.0; C: 0.25 + 0.25 = 0.5
        List<String> candidates = graph.expansionCandidates(List.of("Hub"), 3);
        assertEquals(List.of("A", "B", "C"), candidates);
    }
}
