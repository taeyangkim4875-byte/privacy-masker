package com.piimask.app.job;

import com.piimask.app.batch.ExecutionRepository;
import com.piimask.app.batch.JobRunner;
import com.piimask.app.batch.JobScheduler;
import com.piimask.app.connection.ConnectionDtos.ColumnInfo;
import com.piimask.app.connection.ConnectionService;
import com.piimask.app.connection.SchemaService;
import com.piimask.app.job.JobDtos.ColumnSpec;
import com.piimask.app.job.JobDtos.JobRequest;
import com.piimask.app.job.JobDtos.JobView;
import com.piimask.app.support.ApiException;
import com.piimask.app.support.AuditService;
import com.piimask.app.support.Sql;
import com.piimask.core.EntityType;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class JobService {

    private final JobRepository repo;
    private final ConnectionService connections;
    private final SchemaService schema;
    private final TargetTableService target;
    private final ExecutionRepository executions;
    private final JobRunner runner;
    private final JobScheduler scheduler;
    private final AuditService audit;

    public JobService(JobRepository repo, ConnectionService connections, SchemaService schema,
                      TargetTableService target, ExecutionRepository executions,
                      @Lazy JobRunner runner, @Lazy JobScheduler scheduler, AuditService audit) {
        this.repo = repo;
        this.connections = connections;
        this.schema = schema;
        this.target = target;
        this.executions = executions;
        this.runner = runner;
        this.scheduler = scheduler;
        this.audit = audit;
    }

    public List<JobView> list() {
        return repo.findAll().stream().map(this::view).toList();
    }

    public JobView getView(long id) {
        return view(repo.get(id));
    }

    public JobView create(JobRequest r) {
        JobDef def = toDef(0, r, newSalt());
        if (Boolean.TRUE.equals(r.createTarget())) {
            target.create(def);
        }
        long id = repo.insert(def);
        audit.log("JOB_CREATE", "job#" + id + " " + def.name(), "OK");
        scheduler.refresh(id);
        return getView(id);
    }

    public JobView update(long id, JobRequest r) {
        JobDef existing = repo.get(id);
        if (runner.isRunning(id)) {
            throw ApiException.conflict("실행 중인 작업은 수정할 수 없습니다");
        }
        JobDef def = toDef(id, r, existing.hashSalt());
        if (Boolean.TRUE.equals(r.createTarget())) {
            target.create(def);
        }
        repo.update(id, def);
        if (!existing.sourceTable().equals(def.sourceTable()) || !existing.pkColumn().equals(def.pkColumn())
                || existing.connectionId() != def.connectionId()
                || !existing.targetTable().equals(def.targetTable())) {
            repo.resetWatermark(id);
        }
        audit.log("JOB_UPDATE", "job#" + id + " " + def.name(), "OK");
        scheduler.refresh(id);
        return getView(id);
    }

    public void delete(long id) {
        JobDef j = repo.get(id);
        if (runner.isRunning(id)) {
            throw ApiException.conflict("실행 중인 작업은 삭제할 수 없습니다");
        }
        scheduler.cancel(id);
        repo.delete(id);
        audit.log("JOB_DELETE", "job#" + id + " " + j.name(), "OK");
    }

    public void resetWatermark(long id) {
        JobDef j = repo.get(id);
        if (runner.isRunning(id)) {
            throw ApiException.conflict("실행 중에는 초기화할 수 없습니다");
        }
        repo.resetWatermark(id);
        audit.log("JOB_RESET_WATERMARK", "job#" + id + " " + j.name(), "OK");
    }

    /** 요청을 검증해 JobDef 로 만든다. 미리보기(저장 전)에서도 사용 */
    public JobDef toDef(long id, JobRequest r, String salt) {
        if (r == null) {
            throw ApiException.badRequest("요청 본문이 없습니다");
        }
        if (r.name() == null || r.name().isBlank()) {
            throw ApiException.badRequest("작업 이름을 입력하세요");
        }
        if (r.connectionId() == null) {
            throw ApiException.badRequest("접속 정보를 선택하세요");
        }
        connections.get(r.connectionId());
        Sql.requireIdentifier(r.sourceTable(), "원천 테이블");
        Sql.requireIdentifier(r.targetTable(), "대상 테이블");
        Sql.requireIdentifier(r.pkColumn(), "PK 컬럼");
        if (TargetTableService.MASKED_AT.equalsIgnoreCase(r.pkColumn())) {
            throw ApiException.badRequest("masked_at 은 대상 테이블 예약 컬럼이라 PK 로 쓸 수 없습니다");
        }
        if (r.sourceTable().equals(r.targetTable())) {
            throw ApiException.badRequest("대상 테이블은 원천 테이블과 달라야 합니다(원천에는 쓰지 않습니다)");
        }

        Map<String, ColumnInfo> src = schema.columns(r.connectionId(), r.sourceTable()).stream()
                .collect(Collectors.toMap(ColumnInfo::name, Function.identity()));
        ColumnInfo pk = src.get(r.pkColumn());
        if (pk == null || !pk.primaryKey()) {
            throw ApiException.badRequest("PK 컬럼은 원천 테이블의 기본키여야 합니다: " + r.pkColumn());
        }
        long pkCount = src.values().stream().filter(ColumnInfo::primaryKey).count();
        if (pkCount > 1) {
            throw ApiException.badRequest("복합 기본키 테이블은 아직 지원하지 않습니다(단일 PK 필요)");
        }

        List<ColumnSpec> cols = r.columns() == null ? List.of() : r.columns();
        Set<String> seen = new HashSet<>();
        for (ColumnSpec c : cols) {
            Sql.requireIdentifier(c.column(), "컬럼");
            if (!src.containsKey(c.column())) {
                throw ApiException.badRequest("원천 테이블에 없는 컬럼입니다: " + c.column());
            }
            if (TargetTableService.MASKED_AT.equalsIgnoreCase(c.column())) {
                throw ApiException.badRequest("masked_at 은 대상 테이블 예약 컬럼입니다. 제외로 바꿔 주세요");
            }
            if (c.column().equals(r.pkColumn())) {
                throw ApiException.badRequest("PK 컬럼은 자동으로 복사됩니다. 목록에서 빼 주세요: " + c.column());
            }
            if (!seen.add(c.column())) {
                throw ApiException.badRequest("컬럼이 중복되었습니다: " + c.column());
            }
            if (!JobDef.MASK.equals(c.mode()) && !JobDef.COPY.equals(c.mode())) {
                throw ApiException.badRequest("컬럼 모드는 MASK 또는 COPY 입니다: " + c.column());
            }
            if (c.entities() != null) {
                for (String e : c.entities()) {
                    try {
                        EntityType.valueOf(e);
                    } catch (IllegalArgumentException ex) {
                        throw ApiException.badRequest("알 수 없는 탐지 유형입니다: " + e);
                    }
                }
            }
        }
        if (cols.stream().noneMatch(c -> JobDef.MASK.equals(c.mode()))) {
            throw ApiException.badRequest("마스킹할(MASK) 컬럼을 하나 이상 선택하세요");
        }

        int chunk = r.chunkSize() == null ? 500 : r.chunkSize();
        if (chunk < 10 || chunk > 10_000) {
            throw ApiException.badRequest("청크 크기는 10~10000 사이여야 합니다");
        }
        String cron = r.cron() == null || r.cron().isBlank() ? null : r.cron().trim();
        if (cron != null) {
            if (!CronExpression.isValidExpression(cron)) {
                throw ApiException.badRequest("cron 형식이 올바르지 않습니다. 초 분 시 일 월 요일 6자리입니다. 예: 0 0 2 * * *");
            }
            if (CronExpression.parse(cron).next(LocalDateTime.now()) == null) {
                throw ApiException.badRequest("이 cron 은 실행될 날짜가 없습니다(예: 2월 30일)");
            }
        }
        try {
            return new JobDef(id, r.name().trim(), r.connectionId(), r.sourceTable(), r.pkColumn(), r.targetTable(),
                    chunk, cron, r.enabled() == null || r.enabled(), List.copyOf(cols),
                    JobDef.parsePolicies(r.policies()), salt);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("마스킹 정책 값이 올바르지 않습니다");
        }
    }

    private JobView view(JobDef j) {
        String connName;
        try {
            connName = connections.get(j.connectionId()).name();
        } catch (ApiException e) {
            connName = "(삭제됨)";
        }
        return new JobView(j.id(), j.name(), j.connectionId(), connName, j.sourceTable(), j.pkColumn(),
                j.targetTable(), j.chunkSize(), j.cron(), j.enabled(), j.columns(),
                JobDef.policiesView(j.policies()), repo.watermark(j.id()), runner.isRunning(j.id()),
                executions.last(j.id()));
    }

    private static String newSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
