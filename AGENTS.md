# AGENTS.md

## Repo layout

- Single Maven module **at the repo root** (`pom.xml`, `src/`). Run all commands from here.
- `obsidian_rag/` contains only a stale `README.md` left over from before the project was moved to the root (it is still git-tracked). Never build or edit code there.
- Windows environment: invoke the wrapper as `.\mvnw.cmd`. Quote `-D` args in PowerShell (`.\mvnw.cmd test "-DskipTests"`), otherwise they get split.

## Commands

```powershell
.\mvnw.cmd -q -DskipTests compile   # fast compile check
.\mvnw.cmd test                     # 12 unit tests, no network/DB needed
docker compose up -d                # pgvector Postgres on localhost:5433 (NOT 5432)
.\mvnw.cmd spring-boot:run
```

Runtime env vars required by the app: `NVIDIA_API_KEY` (fail-fast at startup if unset) and `OBSIDIAN_VAULT_PATH`.

## Toolchain quirks

- LangChain4j **1.19.0** is pinned via `langchain4j-bom` import in `dependencyManagement` — add new langchain4j modules without versions.
- Spring Boot 4.1 uses **Jackson 3**: inject `tools.jackson.databind.ObjectMapper` (the auto-configured bean). `com.fasterxml.jackson.*` classes exist only as a transitive dependency of langchain4j — they are NOT Spring beans.
- Embeddings are asymmetric NVIDIA models: two beans `passageEmbeddingModel` (indexing) and `queryEmbeddingModel` (retrieval), differing only in `customParameters(input_type=...)`. Using the wrong one silently degrades retrieval accuracy. All AI calls go to the NVIDIA NIM endpoint (`https://integrate.api.nvidia.com/v1`) — no local models by design.

## Architecture (non-obvious)

- Retrieval is done manually in `rag/RagService`, deliberately NOT via `RetrievalAugmentor`: the service must know exactly which fragments were sent to the LLM for citation validation. Don't "simplify" this into an augmentor.
- Answer pipeline order (in `RagService.chat`): vector search → no-evidence refusal guard (LLM skipped) → `Assistant` structured output (`RagAnswer`) → `FaithfulnessService.validateCitations` (drops fabricated citations) → optional entailment judge → optional single regeneration. Config under `obsidian.rag.faithfulness.*`.
- Incremental indexing state lives in `./data/index-state.json` (path→SHA-256). If vectors and state desync (state deleted, DB kept), rebuild with `POST /api/index?full=true`.
- Obsidian frontmatter parser handles flat keys, comma lists, and `- item` blocks only — no nested YAML.

## Documentation

- Every method (public, protected, and private) must have a JavaDoc comment. Include `@param`, `@return`, and `@throws` tags where applicable; describe non-obvious behavior, not the obvious.
- Whenever a method is created or modified, its JavaDoc must be updated in the same change to accurately reflect the new signature and behavior — never leave stale documentation behind.

## Testing constraints

- Unit tests are pure JUnit (parser/splitter/graph) plus an `ApplicationContextRunner` slice for property binding — they intentionally avoid booting the full context.
- Do NOT add a plain `@SpringBootTest` context-load test: startup instantiates the chat/embedding models (requires live `NVIDIA_API_KEY`) and the pgvector store (requires running Docker). This was removed for exactly that reason.
