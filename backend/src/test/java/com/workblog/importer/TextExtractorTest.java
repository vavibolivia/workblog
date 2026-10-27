package com.workblog.importer;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextExtractorTest {

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void excel_cells_become_text() throws Exception {
        byte[] data;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sh = wb.createSheet("서버");
            var r0 = sh.createRow(0); r0.createCell(0).setCellValue("호스트"); r0.createCell(1).setCellValue("포트");
            var r1 = sh.createRow(1); r1.createCell(0).setCellValue("app-server-01"); r1.createCell(1).setCellValue(8080);
            wb.write(out); data = out.toByteArray();
        }
        String text = TextExtractor.extract(data, "목록.XLSX");
        assertThat(text).contains("호스트").contains("app-server-01").contains("8080");
        assertThat(text).doesNotContain("<");
    }

    @Test
    void word_paragraphs_become_text() throws Exception {
        byte[] data;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("장애 보고서");
            doc.createParagraph().createRun().setText("원인: 디스크 가득 참");
            doc.write(out); data = out.toByteArray();
        }
        assertThat(TextExtractor.extract(data, "report.docx")).contains("장애 보고서").contains("디스크 가득 참");
    }

    @Test
    void pdf_text_is_extracted() throws Exception {
        byte[] data;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(); doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); cs.newLineAtOffset(72, 700);
                cs.showText("Runbook restart nginx gateway"); cs.endText();
            }
            doc.save(out); data = out.toByteArray();
        }
        assertThat(TextExtractor.extract(data, "runbook.pdf")).contains("restart nginx gateway");
    }

    @Test
    void csv_markdown_html_text() throws Exception {
        assertThat(TextExtractor.extract(utf8("이름,값\n알파,\"1,2\"\n"), "a.csv")).contains("알파").contains("1,2");
        assertThat(TextExtractor.extract(utf8("# 제목\n\n**굵게** 본문 [링크](http://x)"), "a.md")).contains("제목").contains("굵게 본문").doesNotContain("**");
        assertThat(TextExtractor.extract(utf8("<html><body><h1>안내</h1><script>x()</script><p>내용</p></body></html>"), "a.html"))
                .contains("안내").contains("내용").doesNotContain("<p>");
        assertThat(TextExtractor.extract(utf8("첫 줄\r\n\r\n   둘째   줄  "), "a.txt")).isEqualTo("첫 줄\n둘째 줄");
    }

    @Test
    void unsupported_returns_null_and_broken_file_throws() throws Exception {
        assertThat(TextExtractor.extract(new byte[]{1, 2, 3}, "photo.png")).isNull();
        assertThat(TextExtractor.extract(new byte[]{1, 2, 3}, "noext")).isNull();
        assertThat(TextExtractor.supports("A.PDF")).isTrue();
        assertThat(TextExtractor.supports("a.hwp")).isFalse();
        assertThatThrownBy(() -> TextExtractor.extract(utf8("not a pdf"), "x.pdf")).isInstanceOf(Exception.class);
    }

    @Test
    void nul_removed_and_truncated_to_200kb_utf8() {
        assertThat(TextExtractor.normalize("a\u0000b")).isEqualTo("ab");
        String big = "가".repeat(100_000); // 300,000 bytes in UTF-8
        String cut = TextExtractor.normalize(big);
        assertThat(cut.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(TextExtractor.MAX_BYTES);
        assertThat(cut.length()).isEqualTo(TextExtractor.MAX_BYTES / 3);
        String emoji = "a" + "😀".repeat(60_000);
        String e = TextExtractor.truncateUtf8(emoji, 9);
        assertThat(e).isEqualTo("a😀😀"); // 1 + 4 + 4 bytes, 쌍을 쪼개지 않음
    }
}
