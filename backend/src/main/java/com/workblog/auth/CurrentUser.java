package com.workblog.auth;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.*;

/** Injects the authenticated {@link com.workblog.user.User} entity into controller methods. */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal(expression = "user")
public @interface CurrentUser {
}
