package com.rag.obsidian_rag.api.dto;

/**
 * A validated citation referencing one specific fragment of one note.
 * {@code quote} is verbatim text from the cited fragment.
 *
 * @param noteTitle     title of the cited note
 * @param filePath      vault-relative path of the cited note
 * @param chunkIndex    zero-based chunk position within the note
 * @param quote         verbatim quote from the fragment
 * @param reasonForUse  short justification for citing this fragment
 */
public record CitationDto(
        String noteTitle,
        String filePath,
        Integer chunkIndex,
        String quote,
        String reasonForUse) {

    /**
     * Converts a validated domain citation into its API shape.
     *
     * @param c domain citation
     * @return DTO copy with identical field values
     */
    public static CitationDto from(com.rag.obsidian_rag.rag.Citation c) {
        return new CitationDto(c.getNoteTitle(), c.getFilePath(), c.getChunkIndex(),
                c.getQuote(), c.getReasonForUse());
    }
}
