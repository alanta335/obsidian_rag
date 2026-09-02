package com.rag.obsidian_rag.parser;

import java.util.List;
import java.util.Map;

/**
 * A parsed Obsidian markdown note with its metadata graph information.
 *
 * @param noteTitle    file name without the .md extension
 * @param relativePath vault-relative path using '/' separators
 * @param body         note text with YAML frontmatter stripped
 * @param contentHash  SHA-256 of the raw file bytes (hex)
 * @param frontmatter  raw frontmatter values (String or List&lt;String&gt;)
 * @param tags         frontmatter + inline tags (deduplicated, order preserved)
 * @param aliases      frontmatter aliases used for wikilink resolution
 * @param links        outgoing [[wikilinks]] extracted from the body and frontmatter
 */
public record ObsidianNote(
        String noteTitle,
        String relativePath,
        String body,
        String contentHash,
        Map<String, Object> frontmatter,
        List<String> tags,
        List<String> aliases,
        List<WikiLink> links) {

    /**
     * Where a wikilink appears in a note; determines its weight in the link graph.
     */
    public enum LinkType {
        /** In the main body text — strongest signal. */
        BODY,
        /** In a footer/references section at the end of the note. */
        FOOTER,
        /** In the YAML frontmatter (tags or other metadata). */
        FRONTMATTER
    }

    /**
     * One outgoing wikilink, decomposed into its parts.
     *
     * @param target link target before any '#' anchor or '|' alias (may include folder path)
     * @param anchor optional heading anchor after '#'
     * @param alias  optional display text after '|'
     * @param type   where the link appears in the note
     */
    public record WikiLink(String target, String anchor, String alias, LinkType type) {

        /**
         * Convenience constructor for body links, for callers that do not track position.
         *
         * @param target link target before any '#' anchor or '|' alias (may include folder path)
         * @param anchor optional heading anchor after '#'
         * @param alias  optional display text after '|'
         */
        public WikiLink(String target, String anchor, String alias) {
            this(target, anchor, alias, LinkType.BODY);
        }
    }
}
