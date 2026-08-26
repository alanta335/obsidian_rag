package com.rag.obsidian_rag.parser;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a parsed note into embeddable {@link TextSegment}s.
 *
 * The note is first cut into markdown sections along headings; each section
 * keeps its heading path (e.g. "Setup › Installation") as metadata and is then
 * recursively split by size. Every segment is prefixed with a header line
 * carrying the note title and heading path so that embeddings and citations
 * retain their context.
 */
public class MarkdownSectionSplitter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    public static final String FILE_PATH = "file_path";
    public static final String NOTE_TITLE = "note_title";
    public static final String HEADING_PATH = "heading_path";
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String CONTENT_HASH = "content_hash";
    public static final String TAGS_CSV = "tags_csv";

    private MarkdownSectionSplitter() {
    }

    public static List<TextSegment> split(ObsidianNote note, int chunkSize, int chunkOverlap) {
        List<TextSegment> result = new ArrayList<>();
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        int chunkIndex = 0;

        for (Section section : sectionsOf(note)) {
            Metadata md = baseMetadata(note);
            if (!section.headingPath.isBlank()) {
                md.put(HEADING_PATH, section.headingPath);
            }
            List<TextSegment> parts = splitter.split(Document.from(section.text, md));
            for (TextSegment part : parts) {
                part.metadata().put(CHUNK_INDEX, chunkIndex++);
                String header = "[" + note.noteTitle();
                if (!section.headingPath.isBlank()) {
                    header += " › " + section.headingPath;
                }
                header += "]";
                result.add(TextSegment.from(header + "\n" + part.text(), part.metadata()));
            }
        }
        return result;
    }

    private record Section(String text, String headingPath) {
    }

    private static List<Section> sectionsOf(ObsidianNote note) {
        List<Section> sections = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        Deque<Integer> depths = new ArrayDeque<>();
        Deque<String> titles = new ArrayDeque<>();

        boolean inFence = false;
        for (String line : note.body().split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                buf.append(line).append('\n');
                continue;
            }
            Matcher h = inFence ? null : HEADING.matcher(trimmed);
            if (h != null && h.matches()) {
                flush(sections, buf, note, joinPath(titles));
                int depth = h.group(1).length();
                String title = h.group(2).trim();
                while (!depths.isEmpty() && depths.peekLast() >= depth) {
                    depths.removeLast();
                    titles.removeLast();
                }
                depths.addLast(depth);
                titles.addLast(title);
                buf.append(line).append('\n');
            } else {
                buf.append(line).append('\n');
            }
        }
        flush(sections, buf, note, joinPath(titles));
        return sections;
    }

    private static void flush(List<Section> sections, StringBuilder buf, ObsidianNote note, String headingPath) {
        String text = buf.toString().trim();
        buf.setLength(0);
        if (text.isBlank()) {
            return;
        }
        sections.add(new Section(text, headingPath == null ? "" : headingPath));
    }

    private static String joinPath(Deque<String> titles) {
        return String.join(" › ", titles);
    }

    private static Metadata baseMetadata(ObsidianNote note) {
        Metadata md = new Metadata()
                .put(FILE_PATH, note.relativePath())
                .put(NOTE_TITLE, note.noteTitle())
                .put(CONTENT_HASH, note.contentHash());
        if (!note.tags().isEmpty()) {
            md.put(TAGS_CSV, String.join(",", note.tags()).toLowerCase());
        }
        return md;
    }
}
