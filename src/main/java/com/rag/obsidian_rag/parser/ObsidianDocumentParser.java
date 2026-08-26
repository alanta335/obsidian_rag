package com.rag.obsidian_rag.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an Obsidian markdown note:
 * <ul>
 *   <li>strips YAML frontmatter into a metadata map (removed from the body)</li>
 *   <li>extracts frontmatter + inline tags (ignoring headings and code fences)</li>
 *   <li>extracts aliases used for wikilink resolution</li>
 *   <li>extracts outgoing [[wikilinks]] incl. #anchors and |aliases</li>
 *   <li>computes a SHA-256 content hash for incremental indexing</li>
 * </ul>
 */
public class ObsidianDocumentParser {

    private static final Pattern WIKI_LINK =
            Pattern.compile("\\[\\[([^\\]\\|#]+)(?:#([^\\]\\|]+))?(?:\\|([^\\]]+))?\\]\\]");
    private static final Pattern INLINE_TAG = Pattern.compile("(?:^|[\\s(\\[{])#([A-Za-z][A-Za-z0-9_/-]*)");
    private static final Pattern HEADING_LINE = Pattern.compile("^#{1,6}\\s.*");

    /**
     * Parses a single markdown file into an {@link ObsidianNote}.
     * A UTF-8 byte-order mark is stripped before processing.
     *
     * @param vaultRoot vault root; used only to compute the relative path
     * @param file      markdown file to read
     * @return parsed note with body, metadata, tags, aliases, links and content hash
     * @throws IOException when the file cannot be read
     */
    public ObsidianNote parse(Path vaultRoot, Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String hash = sha256(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        String relativePath = vaultRoot.relativize(file).toString().replace('\\', '/');

        Map<String, Object> frontmatter = new LinkedHashMap<>();
        String body = stripFrontmatter(text, frontmatter).trim();

        List<String> tags = extractTags(body, frontmatter);
        List<String> aliases = extractAliases(frontmatter);
        List<ObsidianNote.WikiLink> links = extractLinks(body);

        String fileName = file.getFileName().toString();
        String title = fileName.toLowerCase(Locale.ROOT).endsWith(".md")
                ? fileName.substring(0, fileName.length() - 3)
                : fileName;

        return new ObsidianNote(title, relativePath, body, hash,
                Map.copyOf(frontmatter), List.copyOf(tags), List.copyOf(aliases), List.copyOf(links));
    }

    /**
     * Removes a leading {@code ---} frontmatter block, parsing its flat key/value
     * lines into {@code fm}. Notes without a well-formed frontmatter block are
     * returned untouched.
     *
     * @param text full file content
     * @param fm   map receiving frontmatter values (mutated in place)
     * @return body with the frontmatter block removed
     */
    private String stripFrontmatter(String text, Map<String, Object> fm) {
        String[] lines = text.split("\r?\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return text;
        }
        int close = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                close = i;
                break;
            }
        }
        if (close < 0) {
            return text;
        }
        parseFrontmatterLines(lines, 1, close, fm);
        return String.join("\n", Arrays.copyOfRange(lines, close + 1, lines.length));
    }

    /**
     * Parses the supported frontmatter syntax between the given line indexes:
     * flat keys, comma lists, flow lists {@code [a, b]} and {@code - item} blocks.
     * Unsupported syntax (nested YAML, comments) is skipped silently.
     *
     * @param lines file lines
     * @param start first frontmatter line (after the opening ---)
     * @param end   index of the closing --- line (exclusive)
     * @param fm    destination map (mutated in place)
     */
    @SuppressWarnings("unchecked")
    private void parseFrontmatterLines(String[] lines, int start, int end, Map<String, Object> fm) {
        String currentKey = null;
        for (int i = start; i < end; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                if (currentKey != null) {
                    List<String> list = asList(fm.get(currentKey));
                    list.add(stripQuotes(trimmed.substring(2).trim()));
                    fm.put(currentKey, list);
                }
                continue;
            }
            int idx = trimmed.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            currentKey = trimmed.substring(0, idx).trim();
            String value = stripFlowList(trimmed.substring(idx + 1).trim());
            if (value.isEmpty()) {
                fm.put(currentKey, new ArrayList<String>());
            } else if (value.contains(",")) {
                List<String> parts = Arrays.stream(value.split(","))
                        .map(s -> stripQuotes(s.trim()))
                        .filter(s -> !s.isEmpty())
                        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
                fm.put(currentKey, parts);
            } else {
                fm.put(currentKey, stripQuotes(value));
            }
        }
    }

    /**
     * Normalizes an existing frontmatter value into a mutable list so scalar and
     * list forms can both receive appended items.
     *
     * @param existing previously stored value (String, List or null)
     * @return mutable list containing the prior value(s)
     */
    @SuppressWarnings("unchecked")
    private List<String> asList(Object existing) {
        if (existing instanceof List<?> l) {
            return (List<String>) l;
        }
        List<String> list = new ArrayList<>();
        if (existing instanceof String s && !s.isBlank()) {
            list.add(s);
        }
        return list;
    }

    /**
     * Unwraps a YAML flow-list value {@code [a, b]} to its inner text so the shared
     * comma-splitting logic can treat it like a comma list.
     *
     * @param value raw scalar value after ':'
     * @return inner text when wrapped in brackets, otherwise the unchanged input
     */
    private String stripFlowList(String value) {
        if (value.length() >= 2 && value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Removes symmetric surrounding single/double quotes from a scalar value.
     *
     * @param value raw value
     * @return unquoted value, or the input unchanged when not quoted
     */
    private String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Collects deduplicated tags from frontmatter ({@code tag}/{@code tags}) plus
     * inline {@code #tags} found in the body. Headings and code fences never yield tags.
     *
     * @param body        note body without frontmatter
     * @param frontmatter parsed frontmatter values
     * @return tags in first-seen order
     */
    private List<String> extractTags(String body, Map<String, Object> frontmatter) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        collectStrings(frontmatter.get("tag"), tags::add);
        collectStrings(frontmatter.get("tags"), tags::add);

        boolean inFence = false;
        for (String line : body.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence || HEADING_LINE.matcher(trimmed).matches()) {
                continue;
            }
            Matcher m = INLINE_TAG.matcher(line);
            while (m.find()) {
                tags.add(m.group(1));
            }
        }
        return new ArrayList<>(tags);
    }

    /**
     * Feeds a scalar string, or every element of a list value, to the consumer,
     * skipping null and blank items.
     *
     * @param value    frontmatter value (String, List or null)
     * @param consumer receiver for each non-blank item
     */
    @SuppressWarnings("unchecked")
    private void collectStrings(Object value, java.util.function.Consumer<String> consumer) {
        if (value instanceof String s) {
            if (!s.isBlank()) {
                consumer.accept(s);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : (List<Object>) list) {
                if (item != null && !item.toString().isBlank()) {
                    consumer.accept(item.toString());
                }
            }
        }
    }

    /**
     * Reads alias values from the frontmatter keys {@code alias}/{@code aliases}.
     *
     * @param frontmatter parsed frontmatter values
     * @return deduplicated aliases in order of appearance
     */
    private List<String> extractAliases(Map<String, Object> frontmatter) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        collectStrings(frontmatter.get("alias"), aliases::add);
        collectStrings(frontmatter.get("aliases"), aliases::add);
        return new ArrayList<>(aliases);
    }

    /**
     * Extracts [[wikilinks]] including their optional #anchor and |alias parts.
     * Links inside code fences are ignored and duplicates collapse to the first
     * occurrence in document order.
     *
     * @param body note body without frontmatter
     * @return unique outgoing links
     */
    private List<ObsidianNote.WikiLink> extractLinks(String body) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<ObsidianNote.WikiLink> links = new ArrayList<>();
        boolean inFence = false;
        for (String line : body.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher m = WIKI_LINK.matcher(line);
            while (m.find()) {
                String target = m.group(1).trim();
                if (target.isEmpty()) {
                    continue;
                }
                ObsidianNote.WikiLink link = new ObsidianNote.WikiLink(
                        target,
                        m.group(2) == null ? null : m.group(2).trim(),
                        m.group(3) == null ? null : m.group(3).trim());
                if (seen.add(link.target() + "|" + link.anchor() + "|" + link.alias())) {
                    links.add(link);
                }
            }
        }
        return links;
    }

    /**
     * Computes the lowercase hex SHA-256 of raw bytes; serves as the change
     * detector for incremental indexing.
     *
     * @param bytes file content
     * @return 64-character hex digest
     * @throws IllegalStateException when the JVM lacks SHA-256 (never in practice)
     */
    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
