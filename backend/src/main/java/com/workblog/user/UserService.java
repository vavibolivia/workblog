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

    public UserService(UserRepository users, PasswordEncoder encoder, com.workblog.post.PostRepository posts,
                       com.workblog.attachment.AttachmentService attachments) {
        this.users = users;
        this.encoder = encoder;
        this.posts = posts;
        this.attachments = attachments;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAllByOrderByCreatedAtAsc().stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("사용자"));
    }

    @Transactional(readOnly = true)
    public User getByUsername(String username) {
        return users.findByUsername(username).orElseThrow(() -> ApiException.notFound("사용자"));
    }

    public UserResponse create(CreateUserRequest req) {
        if (users.existsByUsername(req.username())) {
            throw ApiException.conflict("이미 사용 중인 아이디입니다: " + req.username());
        }
        User u = new User(req.username(), encoder.encode(req.password()), req.displayName(),
                req.email(), req.department(), req.role());
        return UserResponse.from(users.save(u));
    }

    /** 회원 가입. 아이디는 대소문자 구분 없이 중복을 막는다. active=false 면 관리자 승인 대기. */
    public User signup(SignupRequest req, boolean active) {
        String username = req.username().trim();
        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("이미 사용 중인 아이디입니다: " + username);
        }
        User u = new User(username, encoder.encode(req.password()), req.displayName().trim(),
                blankToNull(req.email()), blankToNull(req.department()), User.Role.USER);
        u.setActive(active);
        return users.save(u);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public UserResponse update(Long id, UpdateUserRequest req, User actor) {
        User u = getById(id);
        u.setDisplayName(req.displayName());
        u.setEmail(req.email());
        u.setDepartment(req.department());
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
            u.setPassword(encoder.encode(req.newPassword()));
        }
        return UserResponse.from(u);
    }

    public void deactivate(Long id, User actor) {
        if (id.equals(actor.getId())) throw ApiException.badRequest("자기 자신을 비활성화할 수 없습니다.");
        User u = getById(id);
        u.setActive(false);
    }

    /** 영구 삭제(관리자): 글을 쓴 적 없는 계정만(테스트 계정·오가입 정리용). 글이 있으면 비활성화를 쓴다. */
    public void purge(Long id, User actor) {
        if (id.equals(actor.getId())) throw ApiException.badRequest("자기 자신은 삭제할 수 없습니다.");
        User u = getById(id);
        long n = posts.countByAuthorId(id);
        if (n > 0) throw ApiException.badRequest("글이 " + n + "건 있는 계정은 삭제할 수 없습니다. 비활성화하세요.");
        attachments.purgeOrphansForUser(id);
        users.delete(u);
    }

    public UserResponse updateProfile(User actor, UpdateProfileRequest req) {
        User u = getById(actor.getId());
        u.setDisplayName(req.displayName());
        u.setEmail(req.email());
        u.setDepartment(req.department());
        return UserResponse.from(u);
    }

    public void changePassword(User actor, ChangePasswordRequest req) {
        User u = getById(actor.getId());
        if (!encoder.matches(req.currentPassword(), u.getPassword())) {
            throw ApiException.badRequest("현재 비밀번호가 올바르지 않습니다.");
        }
        u.setPassword(encoder.encode(req.newPassword()));
    }
}
