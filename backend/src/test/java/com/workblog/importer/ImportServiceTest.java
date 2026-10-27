package com.workblog.importer;

import com.workblog.attachment.Attachment;
import com.workblog.attachment.AttachmentService;
import com.workblog.common.ApiException;
import com.workblog.importer.ImportService.Converted;
import com.workblog.user.User;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/** 파일 → 본문 변환. Spring 컨텍스트·DB 없이 AttachmentService 만 Mockito 로 대체한다. */
class ImportServiceTest {
    private AttachmentService attachments;
    private ImportService service;
    private final User user = new User("u", "p", "사용자", null, null, User.Role.USER);
    private final List<String> storedNames = new ArrayList<>();

    @BeforeEach
    void setUp() {
        attachments = Mockito.mock(AttachmentService.class);
        Mockito.when(attachments.storeBytes(any(), anyString(), any(), any())).thenAnswer(inv -> {
            storedNames.add(inv.getArgument(1));
            return new Attachment("id-" + storedNames.size(), user, inv.getArgument(1), "2026/10/x.png", "image/png", 10, Attachment.Kind.IMAGE);
        });
        Mockito.when(attachments.storeOriginal(any(), anyString(), any(), any())).thenAnswer(inv ->
                new Attachment("orig", user, inv.getArgument(1), "2026/10/o.bin", "application/octet-stream", 10, Attachment.Kind.FILE));
        service = new ImportService(attachments);
    }

    @Test
    void html_takesTitleFromTitleTag_dropsScriptAndStyleSheet_keepsInlineStyle() {
        String html = "<html><head><title>문서 제목</title><style>p{color:red}</style></head><body><h1>문서 제목</h1>"
                + "<p style=\"color:#d7263d;font-size:19px\">본문 <b>굵게</b></p><script>alert(1)</script><a href=\"http://x.io\">링크</a></body></html>";
        Converted c = service.convert(html.getBytes(StandardCharsets.UTF_8), "a.html", "text/html", false, user);
        assertThat(c.title()).isEqualTo("문서 제목");
        assertThat(c.sourceType()).isEqualTo(ImportService.SourceType.HTML);
        assertThat(c.html()).contains("color:#d7263d").contains("<b>굵게</b>").doesNotContain("<script").doesNotContain("<h1>");
        assertThat(c.html()).contains("target=\"_blank\"");
        assertThat(c.warnings()).anyMatch(w -> w.contains("스타일시트"));
        assertThat(c.attachments()).isEmpty();
    }

    @Test
    void html_dataImageBecomesAttachment_relativeImageDropped() {
        String html = "<p>a</p><img src=\"data:image/png;base64,iVBORw0KGgo=\"><img src=\"pics/local.png\">";
        Converted c = service.convert(html.getBytes(StandardCharsets.UTF_8), "b.htm", null, false, user);
        assertThat(storedNames).containsExactly("html-image-1.png");
        assertThat(c.html()).contains("/api/files/id-1").doesNotContain("pics/local.png");
        assertThat(c.warnings()).anyMatch(w -> w.contains("상대 경로"));
        assertThat(c.title()).isEqualTo("b"); // 제목 없음 → 파일 이름
    }

    @Test
    void excel_sheetsBecomeTables_withHeaderMergeAndFormat() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sh = wb.createSheet("서버");
            Row h = sh.createRow(0);
            h.createCell(0).setCellValue("서버"); h.createCell(1).setCellValue("IP"); h.createCell(2).setCellValue("비고");
            Row r1 = sh.createRow(1);
            r1.createCell(0).setCellValue("20번"); r1.createCell(1).setCellValue("10.0.0.20");
            Cell num = r1.createCell(2); num.setCellValue(1234.5);
            CellStyle st = wb.createCellStyle(); st.setDataFormat(wb.createDataFormat().getFormat("#,##0.0")); st.setAlignment(HorizontalAlignment.CENTER); num.setCellStyle(st);
            Row r2 = sh.createRow(2);
            r2.createCell(0).setCellValue("병합"); sh.addMergedRegion(new CellRangeAddress(2, 2, 0, 2));
            Cell f = sh.createRow(3).createCell(2); f.setCellFormula("1+2");
            wb.createSheet("빈시트");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); wb.write(out); xlsx = out.toByteArray();
        }
        Converted c = service.convert(xlsx, "s.xlsx", null, true, user);
        assertThat(c.sourceType()).isEqualTo(ImportService.SourceType.EXCEL);
        assertThat(c.html()).contains("<th><p>서버</p></th>").contains("<td><p>10.0.0.20</p></td>")
                .contains("1,234.5").contains("text-align:center").contains("colspan=\"3\"").contains("<p>3</p>");
        assertThat(c.html()).doesNotContain("<h2>"); // 내용 있는 시트가 하나뿐이면 시트 제목 없음
        assertThat(c.attachments()).extracting(Attachment::getPublicId).containsExactly("orig");
    }

    @Test
    void word_paragraphsHeadingsTablesAndImages() throws Exception {
        byte[] docx;
        try (XWPFDocument d = new XWPFDocument()) {
            XWPFParagraph h = d.createParagraph(); h.setStyle("Heading1"); h.createRun().setText("절차 제목");
            XWPFParagraph p = d.createParagraph(); XWPFRun r = p.createRun(); r.setText("굵은 글"); r.setBold(true);
            XWPFTable t = d.createTable(1, 2); t.getRow(0).getCell(0).setText("칸1"); t.getRow(0).getCell(1).setText("칸2");
            byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=");
            d.createParagraph().createRun().addPicture(new java.io.ByteArrayInputStream(png), org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "a.png", 100, 100);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); d.write(out); docx = out.toByteArray();
        }
        Converted c = service.convert(docx, "p.docx", null, false, user);
        assertThat(c.sourceType()).isEqualTo(ImportService.SourceType.WORD);
        assertThat(c.title()).isEqualTo("절차 제목");
        assertThat(c.html()).contains("<strong>굵은 글</strong>").contains("<table").contains("칸2").contains("/api/files/id-1");
        assertThat(storedNames).containsExactly("word-image-1.png");
    }

    @Test
    void csv_quotedCommaAndSeparatorDetection_andMs949() {
        String csv = "이름,값\n\"비,씨\",2\n";
        Converted c = service.convert(csv.getBytes(StandardCharsets.UTF_8), "d.csv", "text/csv", false, user);
        assertThat(c.html()).contains("<th><p>이름</p></th>").contains("<td><p>비,씨</p></td>");
        Converted ms = service.convert("이름;값\n가;1".getBytes(Charset.forName("MS949")), "e.csv", null, false, user);
        assertThat(ms.html()).contains("<p>가</p>").contains("<p>1</p>");
        assertThat(ImportService.parseCsv("a\tb\nc\td")).containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void markdown_titleFromFirstHeading_listAndTable() {
        String md = "# 제목\n\n- 항목 **굵게**\n\n| a | b |\n|---|---|\n| 1 | 2 |\n";
        Converted c = service.convert(md.getBytes(StandardCharsets.UTF_8), "m.md", null, false, user);
        assertThat(c.title()).isEqualTo("제목");
        assertThat(c.html()).contains("<li>").contains("<strong>굵게</strong>").contains("<table").doesNotContain("<h1>");
    }

    @Test
    void text_paragraphsSplitOnBlankLines() {
        Converted c = service.convert("첫 문단\n둘째 줄\n\n둘째 문단 <b>".getBytes(StandardCharsets.UTF_8), "t.txt", null, false, user);
        assertThat(c.html()).isEqualTo("<p>첫 문단<br>둘째 줄</p><p>둘째 문단 &lt;b&gt;</p>");
    }

    @Test
    void rejections_unsupportedDocHwpEmptyCorrupt() {
        assertThatThrownBy(() -> service.convert("x".getBytes(), "a.exe", null, false, user)).isInstanceOf(ApiException.class).hasMessageContaining("지원 형식");
        assertThatThrownBy(() -> service.convert("x".getBytes(), "a.doc", null, false, user)).hasMessageContaining("docx");
        assertThatThrownBy(() -> service.convert("x".getBytes(), "a.hwp", null, false, user)).hasMessageContaining("한글");
        assertThatThrownBy(() -> service.convert(new byte[0], "a.html", null, false, user)).hasMessageContaining("비어");
        assertThatThrownBy(() -> service.convert("PK\u0003\u0004junk".getBytes(), "a.xlsx", null, false, user)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.convert("<p>   </p>".getBytes(), "a.html", null, false, user)).hasMessageContaining("본문을 찾지");
    }
}
