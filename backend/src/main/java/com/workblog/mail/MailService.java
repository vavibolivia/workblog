package com.workblog.mail;

import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 메일 보내기. 기본은 꺼져 있다(MAIL_ENABLED=true 로 켬).
 * <ul>
 *   <li>회원 관련 상황에서만 보낸다: 가입(본인 · 승인제면 관리자에게도) / 가입 승인 / 관리자의 계정 생성 / 회원 정보 수정 / 비밀번호 변경.
 *       그 밖에는 댓글에서 @아이디 로 지정한 사람에게만.</li>
 *   <li>호출한 쪽의 DB 트랜잭션이 커밋된 뒤에, 별도 스레드(mailExecutor)에서 보낸다 —
 *       SMTP 왕복 동안 트랜잭션이 잡혀 있지 않고, 롤백된 작업의 메일은 나가지 않는다.</li>
 *   <li>실패는 기록만 하고 호출한 작업은 그대로 성공한다. 주소가 없는 사용자는 건너뛴다.</li>
 *   <li>비밀번호는 어떤 메일에도 싣지 않는다.</li>
 * </ul>
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final String SIGNATURE = "\n\n────────────────────────────\n본 메일은 발신 전용입니다. 회신하지 마세요.\nWorkBlog";

    private final ObjectProvider<JavaMailSender> sender;
    private final Executor executor;
    private final boolean enabled;
    private final String from;
    private final String fromName;
    private final String baseUrl;

    public MailService(ObjectProvider<JavaMailSender> sender,
                       @Qualifier("mailExecutor") Executor executor,
                       @Value("${workblog.mail.enabled:false}") boolean enabled,
                       @Value("${workblog.mail.from:}") String from,
                       @Value("${spring.mail.username:}") String smtpUser,
                       @Value("${workblog.mail.from-name:WorkBlog}") String fromName,
                       @Value("${workblog.public-base-url:}") String baseUrl) {
        this.sender = sender;
        this.executor = executor;
        this.enabled = enabled;
        this.from = from == null || from.isBlank() ? (smtpUser == null ? "" : smtpUser.trim()) : from.trim();
        this.fromName = fromName;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
    }

    public boolean enabled() { return enabled; }

    // ---------- 상황별 ----------

    /**
     * 회원 가입 → 본인에게. pending=true 면 관리자 승인 대기.
     * 가입은 로그인 없이 부를 수 있어 남의 주소로 메일을 보내게 할 수 있다 → 가입자가 적은 이름은 싣지 않고(아이디는 영문·숫자만),
     * 주소 하나에 한 시간 3통 · 전체 한 시간 100통까지만 보낸다.
     */
    public void signedUp(String to, String username, String displayName, boolean pending) {
        if (!enabled || to == null || to.isBlank()) return;
        // 전체 한도를 먼저 본다(전체가 찼는데 주소별 자리만 써 버리지 않게). 가입 자체는 AuthController 가 접속 IP 별로 제한한다.
        String addr = to.trim().toLowerCase();
        if (!signupAll.allow("*", SIGNUP_PER_HOUR)) {
            log.warn("가입 메일 전체 한도 초과로 보내지 않음: {} → {}", username, to);
            return;
        }
        if (!signupSelf.allow(addr, SIGNUP_PER_ADDRESS)) {
            signupAll.undo("*");
            log.warn("가입 메일 주소별 한도 초과로 보내지 않음: {} → {}", username, to);
            return;
        }
        String b = hello(null) + (pending
                ? "WorkBlog 가입 신청을 받았습니다. 관리자가 승인하면 로그인할 수 있습니다.\n\n  아이디 : " + username + '\n'
                : "WorkBlog 에 가입했습니다.\n\n  아이디 : " + username + '\n' + loginLine());
        queue(List.of(nz(to)), pending ? "[WorkBlog] 가입 신청 접수" : "[WorkBlog] 가입 완료", b);
    }

    /** 가입 신청(승인제) → 관리자 모두에게. 한 시간 100건까지만(그 뒤 신청은 사용자 관리 화면에서 본다). */
    public void signupRequested(Collection<String> adminEmails, String username, String displayName, String email, String department) {
        if (!enabled) return;
        if (!signupAdmin.allow("*", SIGNUP_PER_HOUR)) {
            log.warn("가입 신청 알림 메일 한도 초과로 보내지 않음: {}", username);
            return;
        }
        displayName = clean(displayName);
        email = clean(email);
        department = clean(department);
        StringBuilder b = new StringBuilder("새 가입 신청이 있습니다. 승인하면 로그인할 수 있습니다.\n\n");
        b.append("  아이디 : ").append(username).append('\n');
        b.append("  이름 : ").append(displayName).append('\n');
        if (email != null && !email.isBlank()) b.append("  메일 : ").append(email).append('\n');
        if (department != null && !department.isBlank()) b.append("  부서(신청) : ").append(department).append('\n');
        String link = link("/admin/users");
        if (link != null) b.append("\n▶ 사용자 관리: ").append(link);
        queue(adminEmails, "[WorkBlog] 가입 신청 — " + displayName + " (" + username + ")", b.toString());
    }

    /** 관리자가 가입을 승인(계정 활성화) → 본인에게 */
    public void accountApproved(String to, String username, String displayName) {
        String b = hello(displayName) + "WorkBlog 계정이 승인되었습니다. 이제 로그인할 수 있습니다.\n\n  아이디 : " + username + '\n' + loginLine();
        queue(List.of(nz(to)), "[WorkBlog] 계정 승인 완료", b);
    }

    /** 관리자가 계정을 만들어 줌 → 본인에게. 비밀번호는 싣지 않는다. */
    public void accountCreated(String to, String username, String displayName) {
        String b = hello(displayName) + "WorkBlog 계정이 만들어졌습니다.\n\n  아이디 : " + username
                + "\n\n비밀번호는 관리자에게 전달받으세요. 로그인 뒤 [내 정보] 에서 바꿀 수 있습니다.\n" + loginLine();
        queue(List.of(nz(to)), "[WorkBlog] 계정이 만들어졌습니다", b);
    }

    /**
     * 회원 정보 수정 → 본인에게. changes 는 "이름 : 예전 → 새것" 같은 줄, 비어 있으면 보내지 않는다.
     * 메일 주소가 바뀌면 예전 주소와 새 주소 모두에게 보낸다(to 에 둘 다).
     */
    public void profileChanged(Collection<String> to, String username, String displayName, List<String> changes, boolean byAdmin) {
        if (changes.isEmpty()) return;
        StringBuilder b = new StringBuilder(hello(displayName));
        b.append(byAdmin ? "관리자가 WorkBlog 회원 정보를 바꿨습니다." : "WorkBlog 회원 정보가 바뀌었습니다.").append("\n\n");
        b.append("  아이디 : ").append(username).append('\n');
        for (String c : changes) b.append("  ").append(clean(c)).append('\n');
        b.append("\n직접 바꾼 것이 아니거나 내용이 다르면 관리자에게 알려 주세요.");
        String link = link("/me");
        if (link != null) b.append("\n\n▶ 내 정보: ").append(link);
        queue(to, "[WorkBlog] 회원 정보가 변경되었습니다", b.toString());
    }

    /** 비밀번호 변경(본인 또는 관리자) → 본인에게. 비밀번호는 싣지 않는다. */
    public void passwordChanged(String to, String username, String displayName, boolean byAdmin) {
        String b = hello(displayName) + (byAdmin
                ? "관리자가 WorkBlog 계정의 비밀번호를 바꿨습니다.\n\n  아이디 : " + username + "\n\n새 비밀번호는 관리자에게 전달받으세요. 요청한 적이 없다면 관리자에게 알려 주세요.\n"
                : "WorkBlog 계정의 비밀번호가 바뀌었습니다.\n\n  아이디 : " + username + "\n\n직접 바꾼 것이 아니라면 바로 관리자에게 알려 주세요.\n")
                + loginLine();
        queue(List.of(nz(to)), "[WorkBlog] 비밀번호가 변경되었습니다", b);
    }

    /** 댓글에서 @아이디 로 지정한 사람에게. (일반 댓글·답글·글 본문 멘션은 화면 알림만) */
    public void commentMention(String to, String recipientName, String actorName, String postTitle, Long postId, Long commentId, String excerpt) {
        actorName = clean(actorName);
        postTitle = clean(postTitle);
        excerpt = clean(excerpt);
        StringBuilder b = new StringBuilder(hello(recipientName));
        b.append(actorName).append(" 님이 댓글에서 나를 지정했습니다.\n\n");
        b.append("  글 : ").append(postTitle).append('\n');
        if (excerpt != null && !excerpt.isBlank()) b.append("  댓글 : ").append(excerpt).append('\n');
        String link = link("/posts/" + postId + "#comment-" + commentId);
        if (link != null) b.append("\n▶ 보기: ").append(link);
        queue(List.of(nz(to)), "[WorkBlog] 댓글에서 나를 지정했습니다 — " + postTitle, b.toString());
    }

    // ---------- 보내기 ----------

    private void queue(Collection<String> to, String subject, String body) {
        if (!enabled) return;
        Set<String> rcpt = new LinkedHashSet<>();
        for (String a : to) if (a != null && !a.isBlank()) rcpt.add(a.trim());
        if (rcpt.isEmpty()) return;
        String text = body + SIGNATURE;
        String subj = clean(subject);
        Runnable job = () -> executor.execute(() -> deliver(rcpt, subj, text));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { job.run(); }
            });
        } else {
            job.run();
        }
    }

    /** 받는 사람마다 따로 보낸다(서로의 주소가 보이지 않게). */
    private void deliver(Set<String> to, String subject, String text) {
        JavaMailSender s = sender.getIfAvailable();
        if (s == null) {
            log.warn("메일을 보내지 못함(MAIL_HOST 설정 없음): {}", subject);
            return;
        }
        for (String a : to) {
            try {
                SimpleMailMessage m = new SimpleMailMessage();
                if (!from.isBlank()) m.setFrom(fromHeader());
                m.setTo(a);
                m.setSubject(subject);
                m.setText(text);
                s.send(m);
                log.info("메일 보냄: {} → {}", subject, a);
            } catch (Exception e) {
                log.error("메일 보내기 실패: {} → {} ({})", subject, a, e.toString());
            }
        }
    }

    private String fromHeader() {
        try {
            return new InternetAddress(from, fromName, "UTF-8").toString();
        } catch (Exception e) {
            return from;
        }
    }

    private static String hello(String name) {
        String n = clean(name);
        return (n == null || n.isBlank() ? "" : n + " 님, ") + "안녕하세요.\n\n";
    }

    /** 사용자가 적은 값을 메일에 넣기 전에: 줄바꿈·제어 문자를 빈칸으로(제목·본문에 줄을 끼워 넣지 못하게). */
    static String clean(String s) {
        return s == null ? null : s.replaceAll("[\\p{Cntrl}\u2028\u2029]+", " ").trim();
    }

    private static final int SIGNUP_PER_ADDRESS = 3;
    private static final int SIGNUP_PER_HOUR = 100;
    private final com.workblog.common.HourlyThrottle signupSelf = new com.workblog.common.HourlyThrottle();
    private final com.workblog.common.HourlyThrottle signupAll = new com.workblog.common.HourlyThrottle();
    private final com.workblog.common.HourlyThrottle signupAdmin = new com.workblog.common.HourlyThrottle();

    private String loginLine() {
        String link = link("/login");
        return link == null ? "" : "\n▶ 로그인: " + link;
    }

    /** 공개 주소(PUBLIC_BASE_URL)가 없으면 링크 줄을 뺀다. */
    private String link(String path) {
        return baseUrl.isEmpty() ? null : baseUrl + path;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
