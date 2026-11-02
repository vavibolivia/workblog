package com.workblog.datamove;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** 이 서버의 인스턴스 ID(UUID). 처음 시작할 때 만들어 app_instance 에 보관한다. 덤프의 원본 표시·자기 덤프 거부에 쓴다. */
@Component
public class InstanceIdService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InstanceIdService.class);

    private final JdbcTemplate jdbc;
    private volatile String cached;

    public InstanceIdService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void run(ApplicationArguments args) { get(); }

    public String get() {
        String id = cached;
        if (id != null) return id;
        synchronized (this) {
            if (cached != null) return cached;
            List<String> rows = jdbc.queryForList("SELECT instance_id FROM app_instance WHERE id = 1", String.class);
            if (rows.isEmpty()) {
                String fresh = UUID.randomUUID().toString();
                try {
                    jdbc.update("INSERT INTO app_instance (id, instance_id) VALUES (1, ?)", fresh);
                    log.info("인스턴스 ID 생성: {}", fresh);
                } catch (DataIntegrityViolationException raced) {
                    // 다른 프로세스가 먼저 만들었다 → 그 값을 쓴다
                }
                rows = jdbc.queryForList("SELECT instance_id FROM app_instance WHERE id = 1", String.class);
            }
            cached = rows.get(0);
            return cached;
        }
    }
}
