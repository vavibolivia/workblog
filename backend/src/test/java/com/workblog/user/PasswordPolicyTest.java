package com.workblog.user;

import com.workblog.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @Test
    void requires_min_length_letter_and_digit() {
        assertThat(PasswordPolicy.violation("pass1234", 8)).isNull();
        assertThat(PasswordPolicy.violation("pass123", 8)).contains("8자 이상");
        assertThat(PasswordPolicy.violation("password", 8)).contains("영문과 숫자");
        assertThat(PasswordPolicy.violation("12345678", 8)).contains("영문과 숫자");
        assertThat(PasswordPolicy.violation("한글비밀번호1234", 8)).contains("영문과 숫자");
        assertThat(PasswordPolicy.violation(null, 8)).contains("8자 이상");
        assertThat(PasswordPolicy.violation("a1".repeat(51), 8)).contains("100자 이하");
    }

    @Test
    void configured_length_is_used_but_never_below_four() {
        assertThat(new PasswordPolicy(10).minLength()).isEqualTo(10);
        assertThat(new PasswordPolicy(1).minLength()).isEqualTo(4);
        assertThatThrownBy(() -> new PasswordPolicy(10).check("pass1234")).isInstanceOf(ApiException.class).hasMessageContaining("10자");
        new PasswordPolicy(8).check("Abcdefg9");
    }
}
