package com.workblog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "workblog")
public record WorkblogProperties(Jwt jwt, Admin admin, Cors cors, Storage storage, Signup signup, Drawio drawio) {
    public record Jwt(String secret, long expirationMinutes) {}
    public record Admin(String username, String password, String displayName) {}
    public record Cors(List<String> allowedOrigins) {}
    public record Storage(String dir, long maxFileMb) {}
    /** 회원 가입: enabled=false 면 가입 화면·API 를 닫는다. approval=true 면 가입 계정을 비활성으로 만들고 관리자가 활성화한다. */
    public record Signup(boolean enabled, boolean approval) {}
    /** draw.io 편집기 주소(embed 모드). 기본은 공개 embed.diagrams.net, 폐쇄망이면 자체 호스팅 주소로. */
    public record Drawio(String url) {}
}
