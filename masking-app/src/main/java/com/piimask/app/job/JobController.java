package com.piimask.app.job;

import com.piimask.app.batch.ExecutionRepository;
import com.piimask.app.batch.ExecutionView;
import com.piimask.app.batch.JobRunner;
import com.piimask.app.job.JobDtos.DdlView;
import com.piimask.app.job.JobDtos.JobRequest;
import com.piimask.app.job.JobDtos.JobView;
import com.piimask.app.job.JobDtos.PreviewResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobs;
    private final JobRepository repo;
    private final PreviewService preview;
    private final TargetTableService target;
    private final JobRunner runner;
    private final ExecutionRepository executions;

    public JobController(JobService jobs, JobRepository repo, PreviewService preview, TargetTableService target,
                         JobRunner runner, ExecutionRepository executions) {
        this.jobs = jobs;
        this.repo = repo;
        this.preview = preview;
        this.target = target;
        this.runner = runner;
        this.executions = executions;
    }

    @GetMapping
    public List<JobView> list() {
        return jobs.list();
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable("id") long id) {
        return jobs.getView(id);
    }

    @PostMapping
    public JobView create(@RequestBody JobRequest r) {
        return jobs.create(r);
    }

    @PutMapping("/{id}")
    public JobView update(@PathVariable("id") long id, @RequestBody JobRequest r) {
        return jobs.update(id, r);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") long id) {
        jobs.delete(id);
    }

    /** 저장 전 설정으로 미리보기 */
    @PostMapping("/preview")
    public PreviewResult previewDraft(@RequestBody JobRequest r,
                                      @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return preview.preview(jobs.toDef(0, r, "preview"), limit);
    }

    @PostMapping("/{id}/preview")
    public PreviewResult previewSaved(@PathVariable("id") long id,
                                      @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return preview.preview(repo.get(id), limit);
    }

    /** 저장 전 설정으로 대상 테이블 DDL 확인 */
    @PostMapping("/ddl")
    public DdlView ddl(@RequestBody JobRequest r) {
        return target.inspect(jobs.toDef(0, r, "ddl"));
    }

    @PostMapping("/{id}/run")
    public Map<String, Long> run(@PathVariable("id") long id) {
        return Map.of("executionId", runner.start(id, "MANUAL"));
    }

    @PostMapping("/{id}/stop")
    public Map<String, Boolean> stop(@PathVariable("id") long id) {
        return Map.of("stopping", runner.requestStop(id));
    }

    @PostMapping("/{id}/reset-watermark")
    public void resetWatermark(@PathVariable("id") long id) {
        jobs.resetWatermark(id);
    }

    @GetMapping("/{id}/executions")
    public List<ExecutionView> executions(@PathVariable("id") long id,
                                          @RequestParam(name = "limit", defaultValue = "20") int limit) {
        return executions.byJob(id, limit);
    }
}
