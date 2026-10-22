package com.piimask.app.batch;

import com.piimask.app.job.JobDef;
import com.piimask.app.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 작업별 cron 스케줄. 작업 저장·삭제 시 refresh/cancel 로 갱신한다.
 * 계획서의 Quartz 는 클러스터·영속 스케줄이 필요해지는 4단계에서 검토한다.
 */
@Component
public class JobScheduler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobRepository jobs;
    private final JobRunner runner;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();

    public JobScheduler(JobRepository jobs, JobRunner runner) {
        this.jobs = jobs;
        this.runner = runner;
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("mask-cron-");
        scheduler.initialize();
    }

    public void refreshAll() {
        jobs.findAll().forEach(j -> refresh(j.id()));
    }

    public void refresh(long jobId) {
        cancel(jobId);
        JobDef j = jobs.get(jobId);
        if (j.enabled() && j.cron() != null && !j.cron().isBlank()) {
            ScheduledFuture<?> f = scheduler.schedule(() -> runner.startIfIdle(jobId, "CRON"), new CronTrigger(j.cron()));
            if (f == null) {
                log.warn("job#{} cron 이 더 이상 실행될 시점이 없어 등록하지 않습니다: {}", jobId, j.cron());
                return;
            }
            scheduled.put(jobId, f);
            log.info("job#{} 스케줄 등록: {}", jobId, j.cron());
        }
    }

    public void cancel(long jobId) {
        ScheduledFuture<?> f = scheduled.remove(jobId);
        if (f != null) {
            f.cancel(false);
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdown();
    }
}
