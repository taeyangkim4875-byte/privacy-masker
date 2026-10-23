package com.piimask.app.dict;

import com.piimask.app.dict.DictionaryService.AddResult;
import com.piimask.app.support.ApiException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 사용자 이름 사전(설정자). 추가 탐지어는 실명일 수 있어 조회도 설정자만 */
@RestController
@RequestMapping("/api/dictionary")
public class DictionaryController {

    public record AddRequest(String kind, List<String> terms) {
    }

    private final DictionaryService service;

    public DictionaryController(DictionaryService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return service.view();
    }

    @PostMapping
    public AddResult add(@RequestBody AddRequest r) {
        if (r == null) {
            throw ApiException.badRequest("요청 본문이 없습니다");
        }
        return service.add(r.kind(), r.terms());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
