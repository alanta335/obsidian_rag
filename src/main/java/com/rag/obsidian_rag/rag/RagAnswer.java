package com.rag.obsidian_rag.rag;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured answer produced by the {@link Assistant}.
 * Enforces grounded answering: the model must declare whether the context was
 * sufficient and cite the fragments it used.
 */
@Data
public class RagAnswer {

    /** The grounded answer; null when contextSufficient == false. */
    private String answer;

    /** False when the provided fragments do not contain enough information. */
    private boolean contextSufficient = true;

    /** Citations backing every claim in the answer. */
    private List<Citation> citations = new ArrayList<>();
}
