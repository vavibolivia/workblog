package com.workblog.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorBody(int status, String error, String message, Instant timestamp) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException e) {
        return body(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("입력값이 올바르지 않습니다.");
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorBody> handleConstraint(ConstraintViolationException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    public ResponseEntity<ErrorBody> handleBadCredentials(Exception e) {
        return body(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않거나 비활성 계정입니다.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorBody> handleDenied(AccessDeniedException e) {
        return body(HttpStatus.FORBIDDEN, "권한이 없습니다.");
    }

    /** 잘못된 쿼리 값(예: sort=BOGUS, status=XYZ, id 가 숫자가 아님) → 400 과 허용 값 안내 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        Class<?> t = e.getRequiredType();
        String allowed = t != null && t.isEnum() ? " (허용: " + java.util.Arrays.stream(t.getEnumConstants()).map(Object::toString).collect(java.util.stream.Collectors.joining(", ")) + ")" : "";
        return body(HttpStatus.BAD_REQUEST, e.getName() + ": '" + e.getValue() + "' 은(는) 올바른 값이 아닙니다" + allowed);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorBody> handleMissingParam(MissingServletRequestParameterException e) {
        return body(HttpStatus.BAD_REQUEST, e.getParameterName() + ": 필수 값입니다.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "요청 본문(JSON)을 읽을 수 없습니다. 형식과 값(enum 등)을 확인하세요.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorBody> handleTooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 큽니다. 최대 크기를 확인하세요.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> handleNotFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> handleMethod(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleOther(Exception e) {
        log.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");
    }

    private ResponseEntity<ErrorBody> body(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(status.value(), status.getReasonPhrase(), message, Instant.now()));
    }
}
