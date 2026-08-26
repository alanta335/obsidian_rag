package com.rag.obsidian_rag.api.dto;

import java.util.List;

/**
 * Grounded answer with faithfulness verdict and full source audit trail.
 *
 * @param answer            model answer; null on refusal or insufficient context
 * @param faithful          entailment-judge verdict; null when judging did not run
 * @param contextSufficient whether the retrieved evidence was enough to answer
 * @param refusal           user-facing refusal text; null unless the request was refused
 * @param regenerated       true when the returned answer is a regeneration that scored better
 * @param citations         validated citations backing the answer
 * @param unsupportedClaims claims the judge could not ground (empty when faithful)
 * @param sources           every fragment considered, for client-side auditing
 */
public record ChatResponse(
        String answer,
        Boolean faithful,
        Boolean contextSufficient,
        String refusal,
        boolean regenerated,
        List<CitationDto> citations,
        List<String> unsupportedClaims,
        List<SourceItem> sources) {

    /**
     * Factory for the no-evidence refusal variant: answer and citations stay empty
     * while any fragments discovered before refusing are still reported.
     *
     * @param message user-facing refusal text
     * @param sources fragments retrieved before refusing (may be empty)
     * @return response with contextSufficient=false and the refusal message set
     */
    public static ChatResponse refusal(String message, List<SourceItem> sources) {
        return new ChatResponse(null, null, false, message, false, List.of(), List.of(), sources);
    }
}
