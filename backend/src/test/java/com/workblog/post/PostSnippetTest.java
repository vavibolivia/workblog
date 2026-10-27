package com.workblog.post;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostSnippetTest {

    @Test
    void null_content_gives_empty() {
        assertThat(PostService.snippet(null, "x")).isEmpty();
        assertThat(PostService.snippet(null, null)).isEmpty();
    }

    @Test
    void markdown_noise_is_stripped() {
        String md = "# 제목\n\n**굵게** 그리고 __밑줄__ 그리고 ~~취소~~ 와 `코드` [링크](http://x.y) ![그림](img.png)\n\n```java\nint a;\n```\n끝";
        String out = PostService.snippet(md, "");
        assertThat(out).isEqualTo("제목 굵게 그리고 밑줄 그리고 취소 와 코드 링크 끝");
    }

    @Test
    void whitespace_is_collapsed_to_single_spaces() {
        assertThat(PostService.snippet("a\n\n\n  b\t\tc", "")).isEqualTo("a b c");
    }

    @Test
    void empty_or_null_query_returns_beginning_without_ellipsis_when_short() {
        assertThat(PostService.snippet("짧은 글", "")).isEqualTo("짧은 글");
        assertThat(PostService.snippet("짧은 글", null)).isEqualTo("짧은 글");
        assertThat(PostService.snippet("짧은 글", "   ")).isEqualTo("짧은 글");
    }

    @Test
    void empty_query_truncates_long_content_to_160_with_ellipsis() {
        String content = "x".repeat(400);
        String out = PostService.snippet(content, "");
        assertThat(out).hasSize(161).endsWith("…");
        assertThat(out.substring(0, 160)).isEqualTo("x".repeat(160));
    }

    @Test
    void exactly_160_chars_is_not_truncated() {
        String content = "y".repeat(160);
        assertThat(PostService.snippet(content, "")).isEqualTo(content);
    }

    @Test
    void match_in_middle_gives_window_with_ellipses_on_both_sides() {
        String content = "a".repeat(300) + " NEEDLE " + "b".repeat(300);
        String out = PostService.snippet(content, "needle");
        assertThat(out).startsWith("…").endsWith("…").contains("NEEDLE");
        // 창 크기 160 + 양쪽 말줄임
        assertThat(out).hasSize(162);
        // 매치 앞쪽은 창의 1/3(53자) 정도만 보인다
        int idx = out.indexOf("NEEDLE");
        assertThat(idx).isBetween(50, 56);
    }

    @Test
    void match_near_start_has_no_leading_ellipsis() {
        String content = "NEEDLE " + "b".repeat(300);
        String out = PostService.snippet(content, "needle");
        assertThat(out).startsWith("NEEDLE").endsWith("…");
        assertThat(out).hasSize(161);
    }

    @Test
    void match_near_end_has_no_trailing_ellipsis() {
        String content = "a".repeat(300) + " NEEDLE";
        String out = PostService.snippet(content, "needle");
        assertThat(out).startsWith("…").endsWith("NEEDLE");
    }

    @Test
    void match_is_case_insensitive() {
        String out = PostService.snippet("Spring Boot 가이드", "spring boot");
        assertThat(out).isEqualTo("Spring Boot 가이드");
    }

    @Test
    void query_not_found_falls_back_to_beginning() {
        String content = "c".repeat(200);
        String out = PostService.snippet(content, "zzz");
        assertThat(out).isEqualTo("c".repeat(160) + "…");
    }

    @Test
    void query_matching_inside_stripped_markdown_still_found_in_plain_text() {
        String out = PostService.snippet("# 설치 **방법**", "설치 방법");
        assertThat(out).isEqualTo("설치 방법");
    }
}
