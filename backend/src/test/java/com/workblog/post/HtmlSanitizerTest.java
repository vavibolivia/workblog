package com.workblog.post;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** HtmlSanitizer 단위 테스트. (surefire -Dtest=클래스명 필터가 @Nested 를 건너뛰므로 평평하게 둔다) */
class HtmlSanitizerTest {

    // ---------- clean(): 위험 요소 제거 ----------

    @Test
    void clean_script_tag_and_its_body_are_dropped() {
        String out = HtmlSanitizer.clean("<p>hi</p><script>alert('x')</script><p>bye</p>");
        assertThat(out).doesNotContain("<script").doesNotContain("alert");
        assertThat(out).contains("<p>hi</p>").contains("<p>bye</p>");
    }

    @Test
    void clean_inline_event_handlers_are_dropped() {
        String out = HtmlSanitizer.clean("<p onclick=\"steal()\" onmouseover=\"x()\">text</p><img src=\"/api/files/1\" onerror=\"evil()\">");
        assertThat(out).doesNotContainIgnoringCase("onclick").doesNotContainIgnoringCase("onmouseover").doesNotContainIgnoringCase("onerror");
        assertThat(out).contains("text").contains("<img");
    }

    @Test
    void clean_javascript_href_is_dropped_but_http_and_mailto_survive() {
        String out = HtmlSanitizer.clean(
                "<a href=\"javascript:alert(1)\">bad</a>" +
                "<a href=\"https://example.com\" target=\"_blank\">good</a>" +
                "<a href=\"mailto:a@b.c\">mail</a>");
        assertThat(out).doesNotContainIgnoringCase("javascript:");
        assertThat(out).contains("href=\"https://example.com\"").contains("href=\"mailto:a@b.c\"");
        // target 이 있는 링크는 rel 이 강제된다
        assertThat(out).contains("rel=\"noopener noreferrer\"");
    }

    @Test
    void clean_style_with_url_function_is_dropped() {
        String out = HtmlSanitizer.clean("<p style=\"color:url(http://evil/x); font-size:14px\">t</p>");
        assertThat(out).doesNotContainIgnoringCase("url(");
        assertThat(out).contains("font-size:14px");
    }

    @Test
    void clean_style_properties_outside_allowlist_are_dropped() {
        String out = HtmlSanitizer.clean("<p style=\"position:absolute; display:none; color:red\">t</p>");
        assertThat(out).doesNotContain("position").doesNotContain("display");
        assertThat(out).contains("color:red");
    }

    @Test
    void clean_style_attribute_removed_entirely_when_nothing_survives() {
        String out = HtmlSanitizer.clean("<p style=\"position:absolute\">t</p>");
        assertThat(out).isEqualTo("<p>t</p>");
    }

    @Test
    void clean_iframe_object_embed_are_dropped() {
        String out = HtmlSanitizer.clean("<iframe src=\"https://x\"></iframe><object data=\"x\"></object><embed src=\"x\"><p>k</p>");
        assertThat(out).doesNotContain("<iframe").doesNotContain("<object").doesNotContain("<embed");
        assertThat(out).contains("<p>k</p>");
    }

    @Test
    void clean_img_with_disallowed_src_is_removed_entirely() {
        String out = HtmlSanitizer.clean(
                "<img src=\"data:image/png;base64,AAAA\">" +
                "<img src=\"javascript:alert(1)\">" +
                "<img src=\"ftp://host/a.png\">" +
                "<img src=\"relative/a.png\">" +
                "<p>k</p>");
        assertThat(out).doesNotContain("<img");
        assertThat(out).contains("<p>k</p>");
    }

    @Test
    void clean_null_and_blank_input_give_empty_string() {
        assertThat(HtmlSanitizer.clean(null)).isEmpty();
        assertThat(HtmlSanitizer.clean("   ")).isEmpty();
    }

    // ---------- clean(): 에디터 서식 보존 ----------

    @Test
    void clean_allowed_style_properties_are_kept() {
        String style = "color:#ff0000;background-color:rgb(255, 255, 0);font-family:'Nanum Gothic', sans-serif;" +
                       "font-size:18px;line-height:1.8;text-align:center;margin-left:40px";
        String out = HtmlSanitizer.clean("<p style=\"" + style + "\">t</p>");
        for (String decl : List.of("color:#ff0000", "background-color:rgb(255, 255, 0)", "font-family:'Nanum Gothic', sans-serif",
                                   "font-size:18px", "line-height:1.8", "text-align:center", "margin-left:40px")) {
            assertThat(out).as(decl).contains(decl);
        }
    }

    @Test
    void clean_style_on_span_is_kept() {
        String out = HtmlSanitizer.clean("<p><span style=\"color:blue;font-weight:bold\">x</span></p>");
        assertThat(out).contains("<span style=\"color:blue;font-weight:bold;\">x</span>");
    }

    @Test
    void clean_figure_figcaption_img_with_api_files_src_are_kept() {
        String out = HtmlSanitizer.clean(
                "<figure data-align=\"center\" data-width=\"60\">" +
                "<img src=\"/api/files/abc-123\" alt=\"사진\" data-align=\"center\" data-width=\"60\" width=\"300\">" +
                "<figcaption>설명</figcaption></figure>");
        assertThat(out).contains("<figure").contains("<figcaption>설명</figcaption>");
        assertThat(out).contains("src=\"/api/files/abc-123\"").contains("alt=\"사진\"");
        assertThat(out).contains("data-align=\"center\"").contains("data-width=\"60\"").contains("width=\"300\"");
    }

    @Test
    void clean_img_with_http_and_https_src_is_kept() {
        String out = HtmlSanitizer.clean("<img src=\"http://h/a.png\"><img src=\"https://h/b.png\">");
        assertThat(out).contains("src=\"http://h/a.png\"").contains("src=\"https://h/b.png\"");
    }

    @Test
    void clean_table_with_colspan_rowspan_is_kept() {
        String out = HtmlSanitizer.clean(
                "<table data-sheet=\"Sheet1\"><thead><tr><th colspan=\"2\">머리</th></tr></thead>" +
                "<tbody><tr><td rowspan=\"2\" colwidth=\"120\">a</td><td>b</td></tr></tbody></table>");
        assertThat(out).contains("<table").contains("data-sheet=\"Sheet1\"")
                .contains("<th colspan=\"2\">머리</th>")
                .contains("rowspan=\"2\"").contains("colwidth=\"120\"")
                .contains("<td>b</td>");
    }

    @Test
    void clean_data_indent_on_blocks_is_kept() {
        String out = HtmlSanitizer.clean("<p data-indent=\"2\">a</p><h2 data-indent=\"1\">b</h2><ul><li data-indent=\"3\">c</li></ul>");
        assertThat(out).contains("<p data-indent=\"2\">a</p>").contains("<h2 data-indent=\"1\">b</h2>").contains("<li data-indent=\"3\">c</li>");
    }

    @Test
    void clean_data_indent_is_not_allowed_on_arbitrary_tags() {
        String out = HtmlSanitizer.clean("<span data-indent=\"2\">a</span>");
        assertThat(out).doesNotContain("data-indent");
    }

    @Test
    void clean_editor_inline_tags_and_pre_language_are_kept() {
        String out = HtmlSanitizer.clean("<p><u>u</u><s>s</s><mark>m</mark><sub>1</sub><sup>2</sup></p><pre data-language=\"java\"><code>x</code></pre><hr>");
        assertThat(out).contains("<u>u</u>").contains("<s>s</s>").contains("<mark>m</mark>")
                .contains("<sub>1</sub>").contains("<sup>2</sup>")
                .contains("<pre data-language=\"java\">").contains("<hr>");
    }

    // ---------- toPlainText() ----------

    @Test
    void toPlainText_tags_are_stripped_and_blocks_become_lines() {
        String out = HtmlSanitizer.toPlainText("<h1>제목</h1><p>첫 <b>문단</b></p><ul><li>하나</li><li>둘</li></ul>");
        assertThat(out).doesNotContain("<");
        List<String> lines = out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        assertThat(lines).containsExactly("제목", "첫 문단", "하나", "둘");
    }

    @Test
    void toPlainText_br_becomes_line_break() {
        String out = HtmlSanitizer.toPlainText("<p>a<br>b</p>");
        List<String> lines = out.lines().map(String::trim).toList();
        assertThat(lines).containsExactly("a", "b");
    }

    @Test
    void toPlainText_table_rows_become_lines() {
        String out = HtmlSanitizer.toPlainText("<table><tr><td>a</td><td>b</td></tr><tr><td>c</td></tr></table>");
        List<String> lines = out.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
        assertThat(lines).containsExactly("a b", "c");
    }

    @Test
    void toPlainText_runs_of_spaces_collapse_and_result_is_trimmed() {
        assertThat(HtmlSanitizer.toPlainText("<p>   a    b   </p>")).isEqualTo("a b");
    }

    @Test
    void toPlainText_null_and_blank_give_empty() {
        assertThat(HtmlSanitizer.toPlainText(null)).isEmpty();
        assertThat(HtmlSanitizer.toPlainText(" ")).isEmpty();
    }

    @Test
    void relativeInternalLinksKept() {
        String out = HtmlSanitizer.clean("<p><a href=\"/posts/22\">서버</a> <a href=\"https://x.io\" target=\"_blank\">밖</a> <a href=\"javascript:alert(1)\">x</a></p>");
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("href=\"/posts/22\""), out);
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("href=\"https://x.io\"") && out.contains("rel=\"noopener noreferrer\""), out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("javascript:"), out);
    }
}
