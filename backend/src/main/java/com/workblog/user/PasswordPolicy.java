package com.workblog.user;

import com.workblog.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 새 비밀번호 규칙(가입 · 본인 변경 · 관리자 생성/재설정): 최소 길이(기본 8, 설정 workblog.security.password-min-length) + 영문과 숫자를 모두 포함.
 * 이미 있는 계정의 로그인에는 적용하지 않는다.
 */
@Component
public class PasswordPolicy {
    public static final int MAX_LENGTH = 100;
    private final int minLength;

    public PasswordPolicy(@Value("${workblog.security.password-min-length:8}") int minLength) {
        this.minLength = Math.max(4, minLength);
    }

    public int minLength() { return minLength; }

    public void check(String password) {
        String v = violation(password, minLength);
        if (v != null) throw ApiException.badRequest(v);
    }

    /** 규칙을 어기면 사용자에게 보일 메시지, 통과하면 null. */
    public static String violation(String password, int minLength) {
        if (password == null || password.length() < minLength) return "비밀번호는 " + minLength + "자 이상이어야 합니다.";
        if (password.length() > MAX_LENGTH) return "비밀번호는 " + MAX_LENGTH + "자 이하여야 합니다.";
        boolean letter = password.chars().anyMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));
        boolean digit = password.chars().anyMatch(c -> c >= '0' && c <= '9');
        if (!letter || !digit) return "비밀번호에는 영문과 숫자를 모두 넣어야 합니다.";
        return null;
    }
}
