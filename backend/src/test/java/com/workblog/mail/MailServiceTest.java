package com.workblog.mail;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MailServiceTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);

    @SuppressWarnings("unchecked")
    private MailService service(boolean enabled, String baseUrl) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new MailService(provider, Runnable::run, enabled, "", "noreply@example.com", "WorkBlog", baseUrl);
    }

    private List<SimpleMailMessage> sent() {
        ArgumentCaptor<SimpleMailMessage> c = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, atLeast(0)).send(c.capture());
        return c.getAllValues();
    }

    @Test
    void disabled_sends_nothing() {
        service(false, "http://wb").passwordChanged("a@example.com", "kim", "김", false);
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void no_address_is_skipped() {
        MailService m = service(true, "http://wb");
        m.passwordChanged(null, "kim", "김", false);
        m.accountCreated(" ", "kim", "김");
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void password_mail_has_id_and_login_link_but_from_falls_back_to_smtp_user() {
        service(true, "http://wb/").passwordChanged("a@example.com", "kim", "김", true);
        SimpleMailMessage m = sent().get(0);
        assertThat(m.getTo()).containsExactly("a@example.com");
        assertThat(m.getFrom()).contains("noreply@example.com");
        assertThat(m.getSubject()).isEqualTo("[WorkBlog] 비밀번호가 변경되었습니다");
        assertThat(m.getText()).contains("관리자가", "아이디 : kim", "▶ 로그인: http://wb/login", "발신 전용");
    }

    @Test
    void no_public_url_means_no_link_line() {
        service(true, "").signedUp("a@example.com", "kim", "김", false);
        assertThat(sent().get(0).getText()).doesNotContain("▶");
    }

    @Test
    void signup_request_goes_to_each_admin_separately_without_blanks_or_duplicates() {
        service(true, "http://wb").signupRequested(Arrays.asList("x@example.com", null, "", "y@example.com", "x@example.com"), "kim", "김", "k@example.com", "운영");
        List<SimpleMailMessage> all = sent();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).getTo()).containsExactly("x@example.com");
        assertThat(all.get(1).getTo()).containsExactly("y@example.com");
        assertThat(all.get(0).getText()).contains("아이디 : kim", "부서(신청) : 운영", "http://wb/admin/users");
    }

    @Test
    void profile_change_lists_changes_and_is_skipped_when_nothing_changed() {
        MailService m = service(true, "http://wb");
        m.profileChanged(List.of("a@example.com"), "kim", "김", List.of(), false);
        verify(sender, never()).send(any(SimpleMailMessage.class));
        m.profileChanged(List.of("new@example.com", "old@example.com"), "kim", "김", List.of("메일 : old@example.com → new@example.com"), false);
        assertThat(sent()).hasSize(2);
        assertThat(sent().get(0).getText()).contains("메일 : old@example.com → new@example.com", "http://wb/me");
    }

    @Test
    void comment_mention_links_to_the_comment() {
        service(true, "http://wb").commentMention("a@example.com", "김", "이", "글 제목", 31L, 7L, "확인 부탁");
        SimpleMailMessage m = sent().get(0);
        assertThat(m.getSubject()).isEqualTo("[WorkBlog] 댓글에서 나를 지정했습니다 — 글 제목");
        assertThat(m.getText()).contains("이 님이", "댓글 : 확인 부탁", "http://wb/posts/31#comment-7");
    }

    @Test
    void signup_mail_omits_the_name_the_applicant_typed_and_is_limited_per_address() {
        MailService m = service(true, "http://wb");
        for (int i = 0; i < 5; i++) m.signedUp("victim@example.com", "kim" + i, "무료 쿠폰 http://evil.example", false);
        List<SimpleMailMessage> all = sent();
        assertThat(all).hasSize(3);
        assertThat(all.get(0).getText()).doesNotContain("evil").contains("아이디 : kim0");
    }

    @Test
    void line_breaks_in_user_text_cannot_add_lines_to_subject_or_body() {
        service(true, "").signupRequested(List.of("x@example.com"), "kim", "김\r\nBcc: z@example.com", null, "운영\n권한 : 관리자");
        SimpleMailMessage m = sent().get(0);
        assertThat(m.getSubject()).doesNotContain("\n", "\r");
        assertThat(m.getText()).contains("이름 : 김 Bcc: z@example.com\n", "부서(신청) : 운영 권한 : 관리자\n");
    }

    @Test
    void failure_for_one_recipient_does_not_stop_the_rest_or_throw() {
        doThrow(new MailSendException("x")).doNothing().when(sender).send(any(SimpleMailMessage.class));
        service(true, "").signupRequested(List.of("x@example.com", "y@example.com"), "kim", "김", null, null);
        verify(sender, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void inside_a_transaction_mail_waits_for_commit_and_is_dropped_on_rollback() {
        MailService m = service(true, "");
        TransactionSynchronizationManager.initSynchronization();
        try {
            m.signedUp("a@example.com", "kim", "김", false);
            verify(sender, never()).send(any(SimpleMailMessage.class));
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            verify(sender, times(1)).send(any(SimpleMailMessage.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
