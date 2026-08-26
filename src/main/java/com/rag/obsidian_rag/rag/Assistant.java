package com.rag.obsidian_rag.rag;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Strictly-grounded question answering over the Obsidian vault.
 *
 * The implementation is built with {@code AiServices.create(...)}; retrieval
 * is performed by {@link RagService} so that exactly the sent fragments are
 * known for citation validation and faithfulness judging.
 */
public interface Assistant {

    /**
     * Produces a structured, fully cited answer for the given question.
     *
     * @param question user question
     * @param context  pre-retrieved vault fragments; the caller owns retrieval
     * @return grounded {@link RagAnswer}; when the evidence is insufficient the model
     *         sets contextSufficient=false with a null answer and no citations
     */
    @SystemMessage("""
            You are a precise research assistant answering questions about the user's personal Obsidian vault.

            HARD RULES (non-negotiable):
            1. Base your answer ONLY on the fragments provided in <context>. Never use outside knowledge, assumptions, or speculation.
            2. If the context does not contain enough information to answer, set contextSufficient=false, leave answer=null and citations=[].
            3. Every claim you make MUST be backed by at least one entry in citations[].
            4. Each citation must reference the exact noteTitle and chunkIndex shown in the fragment header, include a VERBATIM quote copied from that fragment, and a short reasonForUse explaining why it supports the claim.
            5. Never invent notes, titles, chunk indices, quotes, or sources.
            Respond only with the required structured output.
            """)
    @UserMessage("""
            Question: {{question}}

            Context fragments from the vault:
            <context>
            {{context}}
            </context>
            """)
    RagAnswer answer(@V("question") String question, @V("context") String context);
}
