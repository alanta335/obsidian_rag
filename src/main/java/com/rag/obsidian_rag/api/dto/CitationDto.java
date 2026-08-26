package com.rag.obsidian_rag.api.dto;

/**
 * A validated citation referencing one specific fragment of one note.
 * {@code quote} is verbatim text from the cited fragment.
 */
public record CitationDto(
        String noteTitle,
        String filePath,
        Integer chunkIndex,
        String quote,
        String reasonForUse) {

    public static CitationDto from(com.rag.obsidian_rag.rag.Citation c) {
        return new CitationDto(c.getNoteTitle(), c.getFilePath(), c.getChunkIndex(),
                c.getQuote(), c.getReasonForUse());
    }
}
