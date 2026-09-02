# TODO Plan List

Improvements to the linked-context expansion / retrieval flow in `RagService` and `LinkGraphService`.

## Shared context (read first)

**Retrieval flow today** (`RagService.chat`, RagService.java:88):
1. Embed the question with the query embedding model.
2. Primary vector search over the whole store: `maxResults=6`, `minScore=0.55`, no filter (RagService.java:92-93).
3. If fewer than `minResults=1` fragments pass, refuse (no LLM call).
4. Linked-context expansion (`expandLinkedContext`, RagService.java:216): graph picks top-3 connected pages by link count, then a per-page vector search at `linkedMinScore=0.30`, `linkedSegmentsPerNote=2`.
5. Primary + linked fragments concatenated into the prompt `<context>`; the model answers with citations.

**The two problems this plan fixes:**
- **Arbitrary dual thresholds.** Primary uses 0.55, linked uses 0.30. A fragment scoring 0.54 in primary is rejected, but 0.31 in a linked page is accepted — the bar depends on which bucket the fragment landed in, not its quality.
- **Graph pre-filter before content.** The graph picks top-3 connected pages *before* any content is examined, so a highly-similar-but-mildly-connected page is excluded.

**Non-negotiable invariant:** The exact fragment list sent to the model (`sentFragments`, RagService.java:110-114) must remain the same list used for citation validation (`validateCitations`, RagService.java:118). Any change to selection must preserve this audit boundary, or fabricated-citation detection breaks.

**Config to extend** (`VaultProperties.Retrieval`, VaultProperties.java:47): currently `maxResults=6`, `minScore=0.55`, `minResults=1`, `expandLinkedContext=true`, `linkedMinScore=0.30`, `linkedMaxNotes=3`, `linkedSegmentsPerNote=2`. New knobs are listed per item.

---

## 1. Hybrid retrieval — two-tier page selection (double-counted + top-K) — TAKEN

**Goal:** Replace the two-bucket (primary vs linked) selection with a two-tier page-selection strategy. Pages strong on **both** signals (graph connectivity *and* vector similarity) are guaranteed a slot, then the pool is filled with the best remaining pages. The inner fragment vector search runs over this final pool.

**Change:** Stop treating primary and linked as two buckets, and stop pre-filtering pages by connection count. Select pages in two tiers:

1. **Build the candidate pool** — get **all** connected pages from the graph (every page linked to/from the hits, no top-N pre-filter), plus the primary hits. Run the vector search over this pool (ideally one store-wide search filtered to the connected-page set, rather than one search per page).
2. **Normalize both scores to 0–1** so neither signal silently dominates:
   - `vectorScore` is already 0–1 (embedding similarity).
   - `graphScore` is a raw connection count (0, 1, 2, …) — normalize it, e.g. `graphScore / maxGraphScore` across the pool, so it is also 0–1. Without this, a page connected to 5 hits always beats a page connected to 1 regardless of the weights.
3. **Tier 1 — double-counted pages (guaranteed slots):** a page is *double-counted* if its **graph score ≥ `graphThreshold`** AND its **vector score ≥ `vectorThreshold`**. These pages are strong on both signals and are always included. Two knobs: `graphThreshold` and `vectorThreshold`.
4. **Tier 2 — fill with top-K:** take the top-K pages by combined score (`combined = w1 * graphScore + w2 * vectorScore`, `w1 + w2 = 1`), **excluding** any page already in the double-counted set. This fills the pool with the best remaining pages without wasting slots on duplicates.
5. **Final pool = double-counted pages + top-K pages.** Run the inner fragment vector search over this pool and take fragments with **vector score ≥ `fragmentThreshold`**. If the resulting context exceeds the model's context-size limit (or the configured max context length), **fall back to taking the top fragments by score** (truncate the ranked list to fit the limit) instead of failing.

**Rationale:** The double-counted pages are the strongest combined signal and must never be lost to a formula quirk (e.g. a page strong on both signals losing to many single-signal pages ahead of it in a pure top-K ranking). Tier 2 then adds diversity from the best of the remainder.

**Fragment selection (after page selection):** take fragments from the selected pages with vector score ≥ `fragmentThreshold` (per-fragment). If the context exceeds the size limit, fall back to the top fragments by score, truncated to fit. Add a per-page cap if balanced representation is wanted.

**Files to touch:**
- `src/main/java/com/rag/obsidian_rag/rag/RagService.java` — `chat` (RagService.java:88) and `expandLinkedContext` (RagService.java:216): build the pool, normalize, compute double-counted + top-K sets, run inner search, truncate. The `0.55`/`0.30` split disappears.
- `src/main/java/com/rag/obsidian_rag/graph/LinkGraphService.java` — may need a method to return **all** connected pages (not just top-N) and their graph scores, e.g. a variant of `expansionCandidates` without the limit.
- `src/main/java/com/rag/obsidian_rag/config/VaultProperties.java` — `Retrieval` (VaultProperties.java:47) needs new config: `graphThreshold` and `vectorThreshold` (double-counted), `K` (top-K fill), `w1`/`w2` weights, `fragmentThreshold` (fragment selection), and a max context length for the fallback.
- `src/test/java/com/rag/obsidian_rag/rag/` — add/adjust tests for the two-tier selection and normalization.

**Invariant to preserve:** See "Non-negotiable invariant" in Shared context above.
