package com.workblog.ops;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 운영 자동화(정리 스케줄러) 활성화. 스케줄 자체는 CleanupScheduler 의 @Scheduled 에 있다. */
@Configuration
@EnableScheduling
public class OpsConfig {
}
