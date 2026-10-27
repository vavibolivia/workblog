package com.workblog.attachment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentTextServiceTest {

    @Test
    void describe_marks_cut_head_and_tail() {
        // 일치 위치 100, 앞 60자부터 200자 → 앞뒤 모두 잘림
        assertThat(AttachmentTextService.describe("목록.xlsx", "  서버   목록  ", 100, 1000)).isEqualTo("목록.xlsx — …서버 목록…");
        // 맨 앞에서 일치, 전체가 짧음 → 말줄임 없음
        assertThat(AttachmentTextService.describe("a.txt", "짧은 내용", 1, 5)).isEqualTo("a.txt — 짧은 내용");
        // 위치를 못 찾았으면(0) 앞부분, 뒤는 남았으면 말줄임
        assertThat(AttachmentTextService.describe("a.pdf", "앞부분", 0, 500)).isEqualTo("a.pdf — 앞부분…");
    }
}
