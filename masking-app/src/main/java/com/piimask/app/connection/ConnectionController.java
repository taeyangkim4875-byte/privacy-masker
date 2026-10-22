package com.piimask.app.connection;

import com.piimask.app.connection.ConnectionDtos.ColumnInfo;
import com.piimask.app.connection.ConnectionDtos.ConnectionRequest;
import com.piimask.app.connection.ConnectionDtos.ConnectionView;
import com.piimask.app.connection.ConnectionDtos.TableInfo;
import com.piimask.app.connection.ConnectionDtos.TestResult;
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

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionService service;
    private final SchemaService schema;

    public ConnectionController(ConnectionService service, SchemaService schema) {
        this.service = service;
        this.schema = schema;
    }

    @GetMapping
    public List<ConnectionView> list() {
        return service.list();
    }

    @PostMapping
    public ConnectionView create(@RequestBody ConnectionRequest r) {
        return service.create(r);
    }

    @PutMapping("/{id}")
    public ConnectionView update(@PathVariable("id") long id, @RequestBody ConnectionRequest r) {
        return service.update(id, r);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") long id) {
        service.delete(id);
    }

    /** 저장 전 테스트. 수정 화면에서 비밀번호를 비워 두면 existingId 의 저장된 비밀번호를 쓴다 */
    @PostMapping("/test")
    public TestResult test(@RequestBody ConnectionRequest r,
                           @RequestParam(name = "existingId", required = false) Long existingId) {
        return service.test(r, existingId);
    }

    @PostMapping("/{id}/test")
    public TestResult testSaved(@PathVariable("id") long id) {
        return service.test(id);
    }

    @GetMapping("/{id}/tables")
    public List<TableInfo> tables(@PathVariable("id") long id) {
        return schema.tables(id);
    }

    @GetMapping("/{id}/tables/{table}/columns")
    public List<ColumnInfo> columns(@PathVariable("id") long id, @PathVariable("table") String table) {
        return schema.columns(id, table);
    }
}
