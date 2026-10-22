package com.piimask.app.batch;

import com.piimask.app.connection.ConnectionService;
import com.piimask.app.job.JobDtos.JobView;
import com.piimask.app.job.JobService;
import com.piimask.app.support.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final ConnectionService connections;
    private final JobService jobs;
    private final ExecutionRepository executions;
    private final AuditService audit;

    public DashboardController(ConnectionService connections, JobService jobs,
                               ExecutionRepository executions, AuditService audit) {
        this.connections = connections;
        this.jobs = jobs;
        this.executions = executions;
        this.audit = audit;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        List<JobView> all = jobs.list();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connectionCount", connections.list().size());
        m.put("jobCount", all.size());
        m.put("runningCount", all.stream().filter(JobView::running).count());
        m.put("scheduledCount", all.stream().filter(j -> j.enabled() && j.cron() != null).count());
        m.put("jobs", all);
        m.put("recentExecutions", executions.recent(10));
        m.put("daily", executions.dailyTotals());
        return m;
    }

    @GetMapping("/executions")
    public List<ExecutionView> recent(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return executions.recent(Math.min(limit, 500));
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return audit.recent(Math.min(limit, 500));
    }
}
