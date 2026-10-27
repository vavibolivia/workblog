package com.workblog.export;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 글 HTML → Word. 결과 docx 를 다시 열어 문단·서식·표·사진을 확인한다. */
class HtmlToDocxTest {
    private static final byte[] PNG = png();

    /** 진짜 PNG(ImageIO 로 만든 4×2). 흔히 돌아다니는 1×1 base64 는 ImageIO 가 EOF 로 거부한다. */
    private static byte[] png() {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    private static XWPFDocument convert(String html) throws Exception {
        byte[] bytes = HtmlToDocx.convert("제목", List.of("작성자 · 2026.10.27"), html, src -> src.contains("ok.png") ? PNG : null);
        return new XWPFDocument(new ByteArrayInputStream(bytes));
    }

    @Test
    void headingsRunsAndStylesAreMapped() throws Exception {
        try (XWPFDocument d = convert("<h2>소제목</h2><p style=\"text-align:center\"><span style=\"color:#d7263d;font-size:19px\">빨강</span> <b>굵게</b> <u>밑줄</u> <s>취소</s> <code>code</code></p>")) {
            List<XWPFParagraph> ps = d.getParagraphs();
            assertThat(ps.get(0).getText()).isEqualTo("제목");
            XWPFParagraph h = ps.stream().filter(p -> p.getText().equals("소제목")).findFirst().orElseThrow();
            assertThat(h.getRuns().get(0).isBold()).isTrue();
            XWPFParagraph body = ps.stream().filter(p -> p.getText().contains("빨강")).findFirst().orElseThrow();
            assertThat(body.getAlignment()).isEqualTo(org.apache.poi.xwpf.usermodel.ParagraphAlignment.CENTER);
            XWPFRun red = body.getRuns().stream().filter(r -> r.text().equals("빨강")).findFirst().orElseThrow();
            assertThat(red.getColor()).isEqualTo("D7263D");
            assertThat(red.getFontSizeAsDouble()).isEqualTo(14.5); // 19px → 14.25pt → 0.5pt 단위 반올림
            assertThat(body.getRuns().stream().filter(r -> r.text().equals("굵게")).findFirst().orElseThrow().isBold()).isTrue();
            assertThat(body.getRuns().stream().filter(r -> r.text().equals("밑줄")).findFirst().orElseThrow().getUnderline()).isNotEqualTo(org.apache.poi.xwpf.usermodel.UnderlinePatterns.NONE);
            assertThat(body.getRuns().stream().filter(r -> r.text().equals("취소")).findFirst().orElseThrow().isStrikeThrough()).isTrue();
            assertThat(body.getRuns().stream().filter(r -> r.text().equals("code")).findFirst().orElseThrow().getFontFamily()).isEqualTo("Consolas");
        }
    }

    @Test
    void tableWithHeaderAndMergeAndIndentedList() throws Exception {
        String html = "<table class=\"rich-table\"><tbody><tr><th colspan=\"2\"><p>머리</p></th></tr><tr><td><p>a</p></td><td style=\"background-color:#fde68a\"><p>b</p></td></tr></tbody></table>"
                + "<ul><li><p>하나</p><ul><li><p>하나-둘</p></li></ul></li></ul><p data-indent=\"2\" style=\"margin-left:64px\">들여쓰기</p><blockquote><p>인용</p></blockquote>";
        try (XWPFDocument d = convert(html)) {
            assertThat(d.getTables()).hasSize(1);
            XWPFTable t = d.getTables().get(0);
            assertThat(t.getNumberOfRows()).isEqualTo(2);
            assertThat(t.getRow(0).getCell(0).getText()).isEqualTo("머리");
            assertThat(t.getRow(0).getCell(0).getParagraphs().get(0).getRuns().get(0).isBold()).isTrue();
            assertThat(t.getRow(0).getCell(0).getCTTc().getTcPr().getHMerge().getVal().toString()).isEqualTo("restart");
            assertThat(t.getRow(1).getCell(1).getColor()).isEqualTo("FDE68A");
            List<XWPFParagraph> ps = d.getParagraphs();
            assertThat(ps.stream().map(XWPFParagraph::getText)).anyMatch(s -> s.startsWith("• 하나")).anyMatch(s -> s.startsWith("◦ 하나-둘"));
            XWPFParagraph ind = ps.stream().filter(p -> p.getText().equals("들여쓰기")).findFirst().orElseThrow();
            assertThat(ind.getIndentationLeft()).isEqualTo(64 * 15);
            XWPFParagraph q = ps.stream().filter(p -> p.getText().equals("인용")).findFirst().orElseThrow();
            assertThat(q.getCTP().getPPr().getPBdr().getLeft()).isNotNull();
        }
    }

    @Test
    void picturesEmbeddedOrReplacedByPlaceholder() throws Exception {
        String html = "<figure class=\"img-figure\" data-align=\"right\" data-width=\"50%\"><img src=\"/api/files/ok.png\" alt=\"그림\"><figcaption>설명</figcaption></figure>"
                + "<p><img src=\"/api/files/missing.png\" alt=\"없음\"></p>";
        try (XWPFDocument d = convert(html)) {
            assertThat(d.getAllPictures()).hasSize(1);
            List<XWPFParagraph> ps = d.getParagraphs();
            XWPFParagraph pic = ps.stream().filter(p -> !p.getRuns().isEmpty() && !p.getRuns().get(0).getEmbeddedPictures().isEmpty()).findFirst().orElseThrow();
            assertThat(pic.getAlignment()).isEqualTo(org.apache.poi.xwpf.usermodel.ParagraphAlignment.RIGHT);
            assertThat(ps.stream().map(XWPFParagraph::getText)).anyMatch(s -> s.equals("설명")).anyMatch(s -> s.contains("[사진: 없음]"));
        }
    }

    @Test
    void cssHelpers() {
        assertThat(HtmlToDocx.color("#abc")).isEqualTo("AABBCC");
        assertThat(HtmlToDocx.color("rgb(255, 0, 10)")).isEqualTo("FF000A");
        assertThat(HtmlToDocx.color("red")).isEqualTo("FF0000");
        assertThat(HtmlToDocx.color("url(x)")).isNull();
        assertThat(HtmlToDocx.fontSizePt("16px")).isEqualTo(12.0);
        assertThat(HtmlToDocx.fontSizePt("1.5em")).isEqualTo(16.5);
        assertThat(HtmlToDocx.fontSizePt("huge")).isNull();
    }
}
