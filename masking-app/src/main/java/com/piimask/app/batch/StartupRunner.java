package com.piimask.app.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 기동 시: 이전 실행 중 끊긴 이력 정리 → 스케줄 등록 */
@Component
public class StartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRunner.class);

    private final ExecutionRepository executions;
    private final JobScheduler scheduler;

    public StartupRunner(ExecutionRepository executions, JobScheduler scheduler) {
        this.executions = executions;
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = executions.failInterrupted();
        if (n > 0) {
            log.warn("재시작 전에 실행 중이던 이력 {}건을 FAILED 로 정리했습니다", n);
        }
        scheduler.refreshAll();
    }
}
