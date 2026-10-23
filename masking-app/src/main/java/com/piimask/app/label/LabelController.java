package com.piimask.app.label;

import com.piimask.app.label.LabelService.AddRequest;
import com.piimask.app.label.LabelService.LabelView;
import com.piimask.app.label.LabelService.QueryRequest;
import com.piimask.app.label.LabelService.SummaryRow;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 교정 라벨. 달기·지우기·행별 조회는 설정자(미리보기를 보는 역할), 요약은 설정자·감사자 */
@RestController
@RequestMapping("/api/labels")
public class LabelController {

    private final LabelService service;

    public LabelController(LabelService service) {
        this.service = service;
    }

    @PostMapping
    public LabelView add(@RequestBody AddRequest r) {
        return service.add(r);
    }

    @PostMapping("/query")
    public List<LabelView> query(@RequestBody QueryRequest q) {
        return service.forRows(q);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    /** connectionId·sourceTable 을 주면 그 테이블의 무효(원천 변경) 수도 센다 */
    @GetMapping("/summary")
    public List<SummaryRow> summary(@RequestParam(required = false) Long connectionId,
                                    @RequestParam(required = false) String sourceTable) {
        return service.summary(connectionId, sourceTable);
    }
}
