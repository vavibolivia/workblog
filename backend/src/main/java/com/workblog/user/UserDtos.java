package com.workblog.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 비밀번호 길이·영문+숫자 규칙은 {@link PasswordPolicy} 가 서비스에서 검사한다(메시지를 한 곳에서). */
public final class UserDtos {
    private UserDtos() {}

    public record UserResponse(Long id, String username, String displayName, String email, String department,
                               User.Role role, boolean active, Instant createdAt) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(),
                    u.getDepartment(), u.getRole(), u.isActive(), u.getCreatedAt());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "영문/숫자/._- 만 사용할 수 있습니다") String username,
            @NotBlank @Size(max = 100) String password,
            @NotBlank @Size(max = 100) String displayName,
            @Email @Size(max = 200) String email,
            @Size(max = 100) String department,
            User.Role role) {}

    /** 회원 가입(본인). 역할은 항상 USER. 아이디 규칙은 관리자 생성과 같다. */
    public record SignupRequest(
            @NotBlank @Size(min = 3, max = 50) @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "영문/숫자/._- 만 사용할 수 있습니다") String username,
            @NotBlank @Size(max = 100) String password,
            @NotBlank @Size(max = 100) String displayName,
            @Email @Size(max = 200) String email,
            @Size(max = 100) String department) {}

    public record UpdateUserRequest(
            @NotBlank @Size(max = 100) String displayName,
            @Email @Size(max = 200) String email,
            @Size(max = 100) String department,
            User.Role role,
            Boolean active,
            @Size(max = 100) String newPassword) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(max = 100) String newPassword) {}

    public record UpdateProfileRequest(@NotBlank @Size(max = 100) String displayName,
                                       @Email @Size(max = 200) String email,
                                       @Size(max = 100) String department) {}
}
