package com.rag.obsidian_rag.rag;

import lombok.Data;

/**
 * A citation referencing one specific fragment of one note.
 * {@code quote} must be verbatim text from the cited fragment.
 */
@Data
public class Citation {

    private String noteTitle;

    private String filePath;

    private Integer chunkIndex;

    private String quote;

    private String reasonForUse;
}
