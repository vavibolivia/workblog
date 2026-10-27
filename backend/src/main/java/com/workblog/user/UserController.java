package com.workblog.user;

import com.workblog.auth.CurrentUser;
import com.workblog.user.UserDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    /** 전사 구성원 목록 — 작성자 필터 등에 쓰이므로 로그인 사용자 모두 조회 가능 */
    @GetMapping
    public List<UserResponse> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req, @CurrentUser User actor) {
        return service.update(id, req, actor);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean purge, @CurrentUser User actor) {
        if (purge) service.purge(id, actor); else service.deactivate(id, actor);
    }

    @PutMapping("/me")
    public UserResponse updateProfile(@Valid @RequestBody UpdateProfileRequest req, @CurrentUser User actor) {
        return service.updateProfile(actor, req);
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req, @CurrentUser User actor) {
        service.changePassword(actor, req);
    }
}
