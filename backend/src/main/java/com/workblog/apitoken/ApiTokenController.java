package com.workblog.apitoken;

import com.workblog.apitoken.ApiTokenDtos.*;
import com.workblog.auth.CurrentUser;
import com.workblog.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 토큰 관리는 웹 로그인(JWT)으로만 한다. API 토큰으로 이 경로를 부르면 403(JwtAuthenticationFilter). */
@Tag(name = "API 토큰", description = "외부 프로그램(Claude Code 등)용 개인 토큰. 웹 로그인(JWT)으로만 관리")
@RestController
@RequestMapping("/api/tokens")
public class ApiTokenController {
    private final ApiTokenService service;

    public ApiTokenController(ApiTokenService service) {
        this.service = service;
    }

    @Operation(summary = "내 토큰 목록")
    @GetMapping
    public List<TokenInfo> list(@CurrentUser User me) {
        return service.list(me);
    }

    @Operation(summary = "토큰 발급", description = "응답의 token 원문은 이때 한 번만 보인다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedToken create(@Valid @RequestBody CreateTokenRequest req, @CurrentUser User me) {
        return service.create(me, req);
    }

    @Operation(summary = "토큰 폐기")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable Long id, @CurrentUser User me) {
        service.revoke(id, me);
    }
}
