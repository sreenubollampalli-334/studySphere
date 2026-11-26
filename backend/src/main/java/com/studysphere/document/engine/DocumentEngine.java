package com.studysphere.document.engine;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface DocumentEngine {
    List<Chunk> extract(InputStream input, String mediaType) throws IOException;

    void index(long ownerId, long documentId, List<IndexedChunk> chunks);

    List<Long> search(long ownerId, long documentId, String question, int limit);

    void delete(long ownerId, long documentId);

    String answer(String question, List<Source> sources);

    final class IndexUnavailable extends RuntimeException {}

    record Chunk(Integer page, String text) {}
    record IndexedChunk(long id, String text) {}
    record Source(long id, Integer page, String text) {}
}
