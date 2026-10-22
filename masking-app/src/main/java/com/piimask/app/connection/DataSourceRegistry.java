package com.piimask.app.connection;

import com.piimask.app.connection.ConnectionDtos.ConnectionSecret;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 접속 정보별 커넥션 풀. 접속 정보가 바뀌면 evict 로 닫고 다시 만든다. */
@Component
public class DataSourceRegistry implements DisposableBean {

    private final Map<Long, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSource get(ConnectionSecret s) {
        return pools.computeIfAbsent(s.id(), id -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setPoolName("src-" + id);
            cfg.setDriverClassName("org.mariadb.jdbc.Driver");
            cfg.setJdbcUrl(s.jdbcUrl());
            cfg.setUsername(s.username());
            cfg.setPassword(s.password());
            cfg.setMaximumPoolSize(4);
            cfg.setMinimumIdle(0);
            cfg.setConnectionTimeout(5_000);
            cfg.setIdleTimeout(60_000);
            return new HikariDataSource(cfg);
        });
    }

    public void evict(long id) {
        HikariDataSource ds = pools.remove(id);
        if (ds != null) {
            ds.close();
        }
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
