package com.workblog.review;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 오래된 글 재검토(workblog.review). 환경변수 REVIEW_STALE_DAYS. */
@ConfigurationProperties(prefix = "workblog.review")
public record ReviewProperties(
        /** 마지막 수정(또는 내용 확인)에서 이 일수가 지나면 '재검토 필요'. 기본 180일. */
        @DefaultValue("180") int staleDays) {
}
