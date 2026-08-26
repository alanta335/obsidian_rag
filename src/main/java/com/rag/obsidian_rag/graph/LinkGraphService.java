package com.rag.obsidian_rag.graph;

import com.rag.obsidian_rag.parser.ObsidianNote;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * In-memory link graph over the vault: title/alias resolution, backlinks,
 * and candidates for linked-context expansion at query time.
 */
@Service
public class LinkGraphService {

    private volatile Map<String, String> titleToPath = Map.of();
    private volatile Map<String, String> normalizedToTitle = Map.of();
    private volatile Map<String, Set<String>> outgoing = Map.of();
    private volatile Map<String, Set<String>> backlinks = Map.of();

    public synchronized void rebuild(Collection<ObsidianNote> notes) {
        Map<String, String> t2p = new HashMap<>();
        Map<String, String> n2t = new HashMap<>();

        for (ObsidianNote note : notes) {
            String norm = normalize(note.noteTitle());
            t2p.put(norm, note.relativePath());
            n2t.putIfAbsent(norm, note.noteTitle());
            for (String alias : note.aliases()) {
                String aliasNorm = normalize(alias);
                n2t.putIfAbsent(aliasNorm, note.noteTitle());
                if (!t2p.containsKey(aliasNorm)) {
                    // alias resolves to the same path as its note
                    t2p.putIfAbsent(aliasNorm, note.relativePath());
                }
            }
        }

        Map<String, Set<String>> out = new HashMap<>();
        Map<String, Set<String>> bl = new HashMap<>();

        for (ObsidianNote note : notes) {
            String sourceNorm = normalize(note.noteTitle());
            for (ObsidianNote.WikiLink link : note.links()) {
                String targetNorm = normalize(stripFolder(link.target()));
                if (!n2t.containsKey(targetNorm)) {
                    continue;
                }
                out.computeIfAbsent(sourceNorm, k -> new LinkedHashSet<>()).add(targetNorm);
                bl.computeIfAbsent(targetNorm, k -> new LinkedHashSet<>()).add(sourceNorm);
            }
        }

        this.titleToPath = Map.copyOf(t2p);
        this.normalizedToTitle = Map.copyOf(n2t);
        this.outgoing = deepCopy(out);
        this.backlinks = deepCopy(bl);
        this.noteCount = notes.size();
    }

    /** Resolves a wikilink target or alias to a vault-relative path, or null. */
    public String resolvePath(String targetOrAlias) {
        return titleToPath.get(normalize(stripFolder(targetOrAlias)));
    }

    /** Titles of notes linking TO the given note (normalized keys). */
    public Set<String> backlinkTitles(String noteTitle) {
        return backlinks.getOrDefault(normalize(noteTitle), Set.of());
    }

    /**
     * Ranks notes connected to the hit notes (outgoing links + backlinks) by
     * connection frequency and returns up to {@code limit} candidate titles.
     */
    public List<String> expansionCandidates(Collection<String> hitTitles, int limit) {
        Map<String, Integer> score = new LinkedHashMap<>();
        Set<String> hits = new LinkedHashSet<>();
        for (String title : hitTitles) {
            hits.add(normalize(title));
        }

        for (String hit : hits) {
            Set<String> targets = outgoing.getOrDefault(hit, Set.of());
            for (String target : targets) {
                merge(score, target);
            }
            for (String source : backlinks.getOrDefault(hit, Set.of())) {
                merge(score, source);
            }
        }

        List<String> ranked = new ArrayList<>(score.keySet());
        ranked.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        List<String> result = new ArrayList<>(limit);
        for (String candidate : ranked) {
            if (hits.contains(candidate)) {
                continue;
            }
            String display = normalizedToTitle.get(candidate);
            if (display != null) {
                result.add(display);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return titleToPath.isEmpty();
    }

    public int noteCount() {
        return noteCount;
    }

    private volatile int noteCount = 0;

    private static void merge(Map<String, Integer> score, String key) {
        score.merge(key, 1, Integer::sum);
    }

    private static String stripFolder(String target) {
        int slash = Math.max(target.lastIndexOf('/'), target.lastIndexOf('\\'));
        return slash >= 0 ? target.substring(slash + 1) : target;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> deepCopy(Map<String, Set<String>> src) {
        Map<String, Set<String>> copy = new HashMap<>();
        src.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        return Map.copyOf(copy);
    }
}
