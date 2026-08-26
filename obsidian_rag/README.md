# Obsidian RAG

A Retrieval-Augmented Generation **framework for Obsidian vaults**, built with
[LangChain4j](https://docs.langchain4j.dev/) and Spring Boot 4.

Ask questions about your personal notes and get **faithful, fully-cited answers** —
the agent is only allowed to answer from your vault, refuses when evidence is missing,
and every claim is backed by verifiable references to the exact note and chunk used.

All AI inference runs on the free [NVIDIA NIM endpoint](https://build.nvidia.com)
(OpenAI-compatible API) — no local models are used.

---

## Features

| Area | What you get |
|---|---|
| **Obsidian parsing** | YAML frontmatter stripped into metadata, `[[wikilinks]]` (+ `#anchors`, `\|aliases`, folder paths), frontmatter + inline `#tags` (code fences ignored) |
| **Smart chunking** | Heading-aware section splitting; every chunk is prefixed with its `[Note › Section]` context path |
| **Link graph** | Alias/title resolution, backlink map, retrieval expansion into linked notes |
| **Incremental indexing** | Per-note SHA-256 hashing — only new/changed/deleted notes are re-embedded |
| **Asymmetric embeddings** | NVIDIA `nv-embedqa-e5-v5` in strict `passage` mode for indexing, `query` mode for search (required for accuracy) |
| **Faithfulness enforcement** | Strict grounding system prompt · no-evidence refusal guard · structured output with mandatory citations · citation validator (blocks fabricated sources) · optional LLM entailment judge with auto-regeneration |
| **REST API** | Index, chat, raw semantic search, status |

## Stack

- Java 21 · Spring Boot 4.1.1
- LangChain4j **1.19.0** (`langchain4j`, `langchain4j-open-ai`, `langchain4j-pgvector`)
- Chat: `deepseek-ai/deepseek-v4-flash-0731` via `https://integrate.api.nvidia.com/v1`
- Embeddings: `nvidia/nv-embedqa-e5-v5` (1024-dim), same endpoint
- Vector store: Postgres 16 + pgvector (Docker)

```
vault/*.md ─► parser ─► link graph ─► section splitter ─► embed(passage) ─► PGVector

question ─► embed(query) ─► similarity search (+linked-context)
        ─► Assistant (strict grounding, structured output)
        ─► citation validator ─► entailment judge ─► regenerate if unfaithful
        ─► { answer, faithful, citations[], sources[] }
```

---

## Getting started

### Prerequisites

- Java 21+, Docker
- A free API key from <https://build.nvidia.com/settings>

### 1. Start the vector database

```bash
docker compose up -d          # Postgres + pgvector on localhost:5433
```

### 2. Configure environment

```bash
export NVIDIA_API_KEY=nvapi-...                 # required
export OBSIDIAN_VAULT_PATH=D:/Notes/MyVault     # path to your vault root
```

Both are also configurable in `src/main/resources/application.yaml`.

### 3. Run

```bash
mvnw.cmd spring-boot:run       # Windows
./mvnw spring-boot:run         # Linux/macOS
```

> The app fails fast at startup if `NVIDIA_API_KEY` is unset or Postgres is unreachable.
> Rotate any key you have shared accidentally — keys never appear in code or config files.

### 4. Index your vault, then ask

```bash
# index (incremental). Add ?full=true to re-embed everything.
curl -X POST localhost:8080/api/index

# ask a question
curl -X POST localhost:8080/api/chat \
     -H "Content-Type: application/json" \
     -d '{"question":"What did I write about Zettelkasten daily reviews?"}'
```

---

## REST API

### `POST /api/chat`

| Field | Description |
|---|---|
| `answer` | Grounded answer (`null` on refusal / insufficient context) |
| `faithful` | Entailment-judge verdict that the claims are supported by cited fragments |
| `contextSufficient` | `false` → the vault had no relevant material |
| `citations[]` | Model-emitted citations **validated server-side**: note title, file path, chunk index, **verbatim quote**, reason for use |
| `unsupportedClaims[]` | Claims the judge could not ground (empty when `faithful=true`) |
| `regenerated` | Whether an unfaithful first attempt was regenerated |
| `sources[]` | Full audit trail of every fragment sent to the model (incl. scores, linked-context hits) |

```json
{
  "answer": "Your daily review checklist lives in the Zettelkasten method note...",
  "faithful": true,
  "contextSufficient": true,
  "refusal": null,
  "regenerated": false,
  "citations": [
    {
      "noteTitle": "Zettelkasten",
      "filePath": "methods/zettelkasten.md",
      "chunkIndex": 3,
      "quote": "Every evening, review today's fleeting notes...",
      "reasonForUse": "Directly describes the daily review procedure"
    }
  ],
  "unsupportedClaims": [],
  "sources": [
    { "noteTitle": "Zettelkasten", "filePath": "methods/zettelkasten.md",
      "chunkIndex": 3, "score": 0.81, "linked": false, "snippet": "..." }
  ]
}
```

Refusal shape (no LLM call is made when nothing relevant exists):

```json
{ "answer": null, "faithful": null, "contextSufficient": false,
  "refusal": "No sufficiently relevant information was found in your vault for this question.",
  "citations": [], "unsupportedClaims": [], "sources": [] }
```

### `GET /api/search?q=...&limit=&minScore=&tag=&note=`

Raw semantic search without generation — auditable retrieval with per-chunk scores.
Optional filters: `tag` (matches the note's tag list) or `note` (exact vault-relative path).

### `POST /api/index?full=false`

Re-indexes the vault. Incremental by default (hash-based); `full=true` re-embeds everything.
Returns counts of indexed/skipped/deleted notes, chunks, and duration.

### `GET /api/status`

Vault reachability, indexed-note count, last index time, and effective model configuration.

---

## Configuration

`obsidian.rag.*` in `application.yaml` (relaxed binding; env vars supported):

```yaml
obsidian:
  rag:
    vault-path: ${OBSIDIAN_VAULT_PATH:}
    chunk-size: 1500            # max chunk chars (~heading context included)
    chunk-overlap: 200
    state-file: ./data/index-state.json
    nvidia:
      base-url: https://integrate.api.nvidia.com/v1
      chat-model: deepseek-ai/deepseek-v4-flash-0731
      embedding-model: nvidia/nv-embedqa-e5-v5
      embedding-dimension: 1024
      batch-size: 16            # texts per embedding request (free-tier friendly)
      temperature: 0.2
      max-tokens: 4096
    retrieval:
      max-results: 6            # top-k fragments
      min-score: 0.55           # below this → refusal guard triggers
      min-results: 1
      expand-linked-context: true   # pull chunks from wikilinked/backlinked notes
      linked-min-score: 0.30
      linked-max-notes: 3
    faithfulness:
      verify: true              # LLM entailment judge (doubles chat calls per question)
      regenerate-if-unfaithful: true
    store:                      # pgvector connection
      host: localhost
      port: 5433
      database: obsidian_rag
      user: postgres
      password: postgres
      table: obsidian_segments
```

## How faithfulness works

1. **Grounding prompt** — hard rules: answer *only* from `<context>` fragments, cite every claim, refuse otherwise.
2. **No-evidence guard** — fewer than `min-results` fragments above `min-score` → structured refusal, LLM never called.
3. **Structured output** — the model must return `{answer, contextSufficient, citations[]}`; each citation needs a verbatim quote from the referenced chunk header.
4. **Citation validation** — emitted `(noteTitle, chunkIndex)` pairs are checked against the fragments actually sent; fabricated ones are dropped.
5. **Entailment judge** *(optional, on by default)* — a second model pass verifies the answer is entailed by the fragments; unfaithful answers are regenerated once, then delivered flagged with `unsupportedClaims`.

## Project layout

```
com.rag.obsidian_rag/
├── api/        REST controllers + DTOs
├── advice/     GlobalExceptionHandler
├── config/     VaultProperties, LangChain4jConfig (all AI beans)
├── graph/      LinkGraphService (aliases, backlinks, expansion ranking)
├── indexing/   VaultIndexer (incremental, batched ingestion)
├── parser/     ObsidianDocumentParser, MarkdownSectionSplitter, ObsidianNote
└── rag/        Assistant, RagAnswer/Citation, RagService, FaithfulnessService
```

## Tests

```bash
mvnw.cmd test
```

12 unit tests cover parsing (frontmatter/tags/wikilinks/fences), heading-aware
splitting, link-graph behavior, and configuration binding — no network or database
required.

## Notes & limitations

- Indexing and chatting require internet access and consume free-tier NVIDIA quota.
- Deleted-note detection relies on incremental state (`state-file`); deleting the state
  file while keeping vectors requires a `?full=true` rebuild.
- Frontmatter parsing covers flat keys, comma lists, and `- item` blocks (not nested YAML).
