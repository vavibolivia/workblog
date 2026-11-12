package com.workblog.user;

import com.workblog.common.ApiException;
import com.workblog.user.UserDtos.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final com.workblog.post.PostRepository posts;
    private final com.workblog.attachment.AttachmentService attachments;
    private final PasswordPolicy passwordPolicy;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.workblog.mail.MailService mail;

    public UserService(UserRepository users, PasswordEncoder encoder, com.workblog.post.PostRepository posts,
                       com.workblog.attachment.AttachmentService attachments, PasswordPolicy passwordPolicy,
                       org.springframework.jdbc.core.JdbcTemplate jdbc, com.workblog.mail.MailService mail) {
        this.jdbc = jdbc;
        this.mail = mail;
        this.users = users;
        this.encoder = encoder;
        this.posts = posts;
        this.attachments = attachments;
        this.passwordPolicy = passwordPolicy;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAllByOrderByCreatedAtAsc().stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("사용자"));
    }

    public UserResponse create(CreateUserRequest req) {
        if (users.existsByUsername(req.username())) {
            throw ApiException.conflict("이미 사용 중인 아이디입니다: " + req.username());
        }
        passwordPolicy.check(req.password());
        User u = new User(req.username(), encoder.encode(req.password()), req.displayName(),
                req.email(), req.department(), req.role());
        u.setPosition(req.position());
        u = users.save(u);
        mail.accountCreated(u.getEmail(), u.getUsername(), u.getDisplayName());
        return UserResponse.from(u);
    }

    /**
     * 회원 가입. 아이디는 대소문자 구분 없이 중복을 막는다. active=false 면 관리자 승인 대기.
     * 부서는 카테고리 열람 제한의 근거라 스스로 정할 수 없다: 승인 대기일 때만 신청값으로 남기고(관리자가 보고 승인), 바로 가입이면 비운다.
     */
    public User signup(SignupRequest req, boolean active) {
        String username = req.username().trim();
        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("이미 사용 중인 아이디입니다: " + username);
        }
        passwordPolicy.check(req.password());
        User u = new User(username, encoder.encode(req.password()), req.displayName().trim(),
                blankToNull(req.email()), active ? null : blankToNull(req.department()), User.Role.USER);
        u.setActive(active);
        u = users.save(u);
        mail.signedUp(u.getEmail(), u.getUsername(), u.getDisplayName(), !active);
        if (!active) {
            List<String> admins = users.findAllByRoleAndActiveTrue(User.Role.ADMIN).stream().map(User::getEmail).toList();
            mail.signupRequested(admins, u.getUsername(), u.getDisplayName(), u.getEmail(), u.getDepartment());
        }
        return u;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public UserResponse update(Long id, UpdateUserRequest req, User actor) {
        User u = getById(id);
        Snapshot before = new Snapshot(u);
        u.setDisplayName(req.displayName());
        u.setEmail(req.email());
        u.setDepartment(req.department());
        if (req.position() != null) u.setPosition(req.position());   // 보내지 않으면(null) 그대로, 빈 글자면 지움
        if (req.role() != null) {
            if (u.getId().equals(actor.getId()) && req.role() != User.Role.ADMIN) {
                throw ApiException.badRequest("자기 자신의 관리자 권한은 해제할 수 없습니다.");
            }
            u.setRole(req.role());
        }
        if (req.active() != null) {
            if (u.getId().equals(actor.getId()) && !req.active()) {
                throw ApiException.badRequest("자기 자신을 비활성화할 수 없습니다.");
            }
            u.setActive(req.active());
        }
        if (req.newPassword() != null && !req.newPassword().isBlank()) {
            passwordPolicy.check(req.newPassword());
            u.setPassword(encoder.encode(req.newPassword()));
            mail.passwordChanged(u.getEmail(), u.getUsername(), u.getDisplayName(), !u.getId().equals(actor.getId()));
        }
        syncPeople(u);
        if (!before.active() && u.isActive()) mail.accountApproved(u.getEmail(), u.getUsername(), u.getDisplayName());
        mail.profileChanged(before.recipients(u), u.getUsername(), u.getDisplayName(), before.changes(u, false), !u.getId().equals(actor.getId()));
        return UserResponse.from(u);
    }

    public void deactivate(Long id, User actor) {
        if (id.equals(actor.getId())) throw ApiException.badRequest("자기 자신을 비활성화할 수 없습니다.");
        User u = getById(id);
        Snapshot before = new Snapshot(u);
        u.setActive(false);
        mail.profileChanged(before.recipients(u), u.getUsername(), u.getDisplayName(), before.changes(u, true), true);
    }

    /** 영구 삭제(관리자): 글을 쓴 적 없는 계정만(테스트 계정·오가입 정리용). 글이 있으면 비활성화를 쓴다. */
    public void purge(Long id, User actor) {
        if (id.equals(actor.getId())) throw ApiException.badRequest("자기 자신은 삭제할 수 없습니다.");
        User u = getById(id);
        long n = posts.countByAuthorId(id);
        // 글·댓글·고친 이력·글에 붙은 첨부는 이 계정을 가리킨다(지우면 기록이 깨짐) → 남아 있으면 비활성화로 안내
        long c = count("SELECT count(*) FROM comments WHERE author_id = ?", id);
        long r = count("SELECT count(*) FROM post_revisions WHERE edited_by = ?", id);
        long a = count("SELECT count(*) FROM attachments WHERE uploader_id = ? AND post_id IS NOT NULL", id);
        if (n + c + r + a > 0) {
            List<String> why = new java.util.ArrayList<>();
            if (n > 0) why.add("글 " + n + "건");
            if (c > 0) why.add("댓글 " + c + "건");
            if (r > 0) why.add("글 수정 기록 " + r + "건");
            if (a > 0) why.add("글에 붙은 첨부 " + a + "개");
            throw ApiException.badRequest("이 계정으로 " + String.join(" · ", why) + " 이 남아 있어 영구 삭제할 수 없습니다. 대신 [비활성화] 하세요(기록은 그대로, 로그인만 막힘).");
        }
        attachments.purgeOrphansForUser(id);
        users.delete(u);
    }

    public UserResponse updateProfile(User actor, UpdateProfileRequest req) {
        User u = getById(actor.getId());
        Snapshot before = new Snapshot(u);
        u.setDisplayName(req.displayName());
        u.setEmail(req.email());
        // 부서는 열람 제한의 근거라 관리자만 바꾼다(관리 > 사용자). 관리자는 자기 부서도 여기서 바꿀 수 있다.
        if (u.getRole() == User.Role.ADMIN) u.setDepartment(req.department());
        // 직급·직책은 열람 권한과 관계없어 본인이 고친다. 보내지 않으면(null) 그대로
        if (req.position() != null) u.setPosition(req.position());
        syncPeople(u);
        mail.profileChanged(before.recipients(u), u.getUsername(), u.getDisplayName(), before.changes(u, true), false);
        return UserResponse.from(u);
    }

    public void changePassword(User actor, ChangePasswordRequest req) {
        User u = getById(actor.getId());
        if (!encoder.matches(req.currentPassword(), u.getPassword())) {
            throw ApiException.badRequest("현재 비밀번호가 올바르지 않습니다.");
        }
        passwordPolicy.check(req.newPassword());
        u.setPassword(encoder.encode(req.newPassword()));
        mail.passwordChanged(u.getEmail(), u.getUsername(), u.getDisplayName(), false);
    }

    /** 수정 전 값. 메일에 "무엇이 어떻게 바뀌었는지"를 싣는 데 쓴다. */
    record Snapshot(String displayName, String email, String department, String position, User.Role role, boolean active) {
        Snapshot(User u) {
            this(u.getDisplayName(), u.getEmail(), u.getDepartment(), u.getPosition(), u.getRole(), u.isActive());
        }

        /** withActivation=false 면 '사용 중지 → 사용'(승인)은 뺀다 — 승인 메일이 따로 간다. */
        List<String> changes(User u, boolean withActivation) {
            List<String> out = new java.util.ArrayList<>();
            line(out, "이름", displayName, u.getDisplayName());
            line(out, "메일", email, u.getEmail());
            line(out, "부서", department, u.getDepartment());
            line(out, "직급·직책", position, u.getPosition());
            if (role != u.getRole()) line(out, "권한", label(role), label(u.getRole()));
            if (active != u.isActive() && (withActivation || !u.isActive())) line(out, "계정 상태", label(active), label(u.isActive()));
            return out;
        }

        /** 메일 주소가 바뀌면 예전 주소에도 알린다. */
        List<String> recipients(User u) {
            List<String> out = new java.util.ArrayList<>();
            if (u.getEmail() != null) out.add(u.getEmail());
            if (email != null && !email.equalsIgnoreCase(u.getEmail() == null ? "" : u.getEmail())) out.add(email);
            return out;
        }

        private static void line(List<String> out, String name, String a, String b) {
            String x = a == null ? "" : a.trim(), y = b == null ? "" : b.trim();
            if (!x.equals(y)) out.add(name + " : " + (x.isEmpty() ? "(없음)" : x) + " → " + (y.isEmpty() ? "(없음)" : y));
        }

        private static String label(User.Role r) { return r == User.Role.ADMIN ? "관리자" : "사용자"; }
        private static String label(boolean active) { return active ? "사용" : "사용 중지"; }
    }

    private long count(String sql, Long id) {
        Long v = jdbc.queryForObject(sql, Long.class, id);
        return v == null ? 0 : v;
    }

    /** 공수 표 인원 행은 계정 이름·부서를 복사해 둔다 → 계정이 바뀌면 같이 바꾼다(계정 부서가 비면 행의 소속은 그대로). */
    private void syncPeople(User u) {
        jdbc.update("UPDATE mm_rows SET person_name = ?, department = COALESCE(NULLIF(TRIM(?), ''), department) WHERE user_id = ?",
                u.getDisplayName(), u.getDepartment() == null ? "" : u.getDepartment(), u.getId());
    }
}
