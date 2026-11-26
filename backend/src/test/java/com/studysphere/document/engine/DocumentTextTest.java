package com.studysphere.document.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextTest {
    @Test
    void preservesActualPdfPageNumbersAcrossBlankPages() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            addPage(pdf, "First page notes");
            pdf.addPage(new PDPage());
            addPage(pdf, "Third page notes");
            pdf.save(bytes);
        }
        var chunks = DocumentText.extract(new ByteArrayInputStream(bytes.toByteArray()), "application/pdf");
        assertThat(chunks).containsExactly(
                new DocumentEngine.Chunk(1, "First page notes"),
                new DocumentEngine.Chunk(3, "Third page notes"));
    }

    @Test
    void leavesTextPagesUnsetAndRejectsMalformedUtf8() throws IOException {
        String notes = "  normalization\n\tkeeps related facts together  ";
        assertThat(DocumentText.extract(new ByteArrayInputStream(notes.getBytes(StandardCharsets.UTF_8)), "text/plain"))
                .containsExactly(new DocumentEngine.Chunk(null, "normalization keeps related facts together"));
        assertThatThrownBy(() -> DocumentText.extract(new ByteArrayInputStream(new byte[] {(byte) 0xc3}), "text/plain"))
                .isInstanceOf(IOException.class);
    }

    private static void addPage(PDDocument pdf, String text) throws IOException {
        var page = new PDPage();
        pdf.addPage(page);
        try (var content = new PDPageContentStream(pdf, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 720);
            content.showText(text);
            content.endText();
        }
    }
}
