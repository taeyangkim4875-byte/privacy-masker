package com.piimask.app.job;

import com.piimask.core.MaskingEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngineConfig {

    /** 1단계: 정규식+검증기, 규칙 이름 탐지기. 3단계에서 ONNX 탐지기를 여기서 추가한다 */
    @Bean
    public MaskingEngine maskingEngine() {
        return MaskingEngine.withDefaults();
    }
}
