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
 * @param links        outgoing [[wikilinks]] extracted from the body
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
     * One outgoing wikilink, decomposed into its parts.
     *
     * @param target link target before any '#' anchor or '|' alias (may include folder path)
     * @param anchor optional heading anchor after '#'
     * @param alias  optional display text after '|'
     */
    public record WikiLink(String target, String anchor, String alias) {
    }
}
