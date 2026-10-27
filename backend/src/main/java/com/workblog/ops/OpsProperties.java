package com.workblog.ops;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 운영 자동화 설정(workblog.ops). WorkblogProperties 와 분리해 둔 이유: 그 레코드는 기능 작업자가 수시로 바꾼다.
 * 환경변수: TRASH_RETENTION_DAYS, ORPHAN_ATTACHMENT_HOURS, CLEANUP_ENABLED (application.yml 참고).
 */
@ConfigurationProperties(prefix = "workblog.ops")
public record OpsProperties(
        /** 휴지통 글을 영구 삭제하기까지 두는 날짜. 기본 30일. */
        @DefaultValue("30") int trashRetentionDays,
        /** 글에 연결되지 않은 업로드(고아 첨부)를 지우기까지 두는 시간. 기본 24시간. */
        @DefaultValue("24") int orphanAttachmentHours,
        /** false 면 매일 03:30 자동 정리를 건너뛴다(관리자 수동 실행은 그대로 가능). */
        @DefaultValue("true") boolean cleanupEnabled) {
}
