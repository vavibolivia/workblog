package com.workblog.notification;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class MentionParserTest {

    @Test
    void finds_usernames_lowercased_in_order_without_duplicates() {
        assertThat(MentionParser.parse("@Kim.Jungh 확인 부탁, @lee_s 도요. @kim.jungh 다시"))
                .containsExactly("kim.jungh", "lee_s");
    }

    @Test
    void ignores_emails_and_too_short_names() {
        assertThat(MentionParser.parse("메일 a.b@example.com 로 보내고 @ab 는 짧음")).isEmpty();
    }

    @Test
    void strips_trailing_punctuation() {
        assertThat(MentionParser.parse("담당은 @park-j. 입니다")).containsExactly("park-j");
        assertThat(MentionParser.parse("(@choi123)")).containsExactly("choi123");
    }

    @Test
    void mention_at_line_start_and_after_korean_text() {
        assertThat(MentionParser.parse("@admin\n안녕@user01")).containsExactly("admin", "user01");
    }

    @Test
    void caps_number_of_mentions() {
        String many = IntStream.range(0, 40).mapToObj(i -> "@user" + i).collect(Collectors.joining(" "));
        assertThat(MentionParser.parse(many)).hasSize(MentionParser.MAX_MENTIONS);
    }

    @Test
    void null_or_without_at_gives_empty() {
        assertThat(MentionParser.parse(null)).isEmpty();
        assertThat(MentionParser.parse("멘션 없음")).isEmpty();
    }
}
