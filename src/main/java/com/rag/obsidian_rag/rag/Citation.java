package com.rag.obsidian_rag.rag;

import lombok.Data;

/**
 * A citation referencing one specific fragment of one note.
 * {@code quote} must be verbatim text from the cited fragment.
 */
@Data
public class Citation {

    /** Title of the cited note as shown in the fragment header. */
    private String noteTitle;

    /** Vault-relative path of the cited note. */
    private String filePath;

    /** Zero-based chunk position within the note; -1 when unknown. */
    private Integer chunkIndex;

    /** Verbatim quote copied from the cited fragment. */
    private String quote;

    /** Short justification of why this fragment supports the claim. */
    private String reasonForUse;
}
