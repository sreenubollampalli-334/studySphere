package com.studysphere.document.engine;

import com.studysphere.document.engine.DocumentEngine.Chunk;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

final class DocumentText {
    private static final int BYTE_LIMIT = 20 * 1024 * 1024;
    private static final int MAX_PAGES = 500;
    private static final int MAX_CHUNKS = 240;
    private static final int CHUNK_LENGTH = 900;
    private static final int OVERLAP = 120;

    private DocumentText() {}

    static List<Chunk> extract(InputStream input, String mediaType) throws IOException {
        byte[] bytes = input.readNBytes(BYTE_LIMIT + 1);
        if (bytes.length > BYTE_LIMIT) throw new IOException("Document exceeds the extraction size limit.");
        var chunks = new ArrayList<Chunk>();
        if ("text/plain".equals(mediaType)) {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            append(chunks, null, text);
        } else if ("application/pdf".equals(mediaType)) {
            try (var pdf = Loader.loadPDF(bytes)) {
                if (pdf.isEncrypted() || !pdf.getCurrentAccessPermission().canExtractContent()) {
                    throw new IOException("Document text extraction is restricted.");
                }
                int pageCount = pdf.getNumberOfPages();
                if (pageCount > MAX_PAGES) throw new IOException("Document exceeds the page limit.");
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                for (int page = 1; page <= pageCount; page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    var output = new PageWriter((MAX_CHUNKS - chunks.size()) * CHUNK_LENGTH);
                    stripper.writeText(pdf, output);
                    append(chunks, page, output.text.toString());
                }
            }
        } else {
            throw new IOException("Unsupported document type.");
        }
        return List.copyOf(chunks);
    }

    private static void append(List<Chunk> chunks, Integer page, String text) throws IOException {
        String clean = text.replaceAll("[\\p{Cc}\\p{Cf}&&[^\\r\\n\\t]]", "")
                .replaceAll("\\s+", " ").strip();
        for (int start = 0; start < clean.length();) {
            if (chunks.size() == MAX_CHUNKS) throw new IOException("Document exceeds the text extraction limit.");
            int end = Math.min(start + CHUNK_LENGTH, clean.length());
            if (end < clean.length()) {
                int whitespace = clean.lastIndexOf(' ', end);
                if (whitespace > start + CHUNK_LENGTH / 2) end = whitespace;
                if (Character.isLowSurrogate(clean.charAt(end))) end--;
            }
            chunks.add(new Chunk(page, clean.substring(start, end).strip()));
            if (end == clean.length()) break;
            start = end - OVERLAP;
            if (Character.isLowSurrogate(clean.charAt(start))) start++;
        }
    }

    private static final class PageWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;

        PageWriter(int limit) { this.limit = limit; }

        @Override
        public void write(char[] buffer, int offset, int length) throws IOException {
            if (length > limit - text.length()) throw new IOException("Document exceeds the text extraction limit.");
            text.append(buffer, offset, length);
        }

        @Override public void flush() {}
        @Override public void close() {}
    }
}
