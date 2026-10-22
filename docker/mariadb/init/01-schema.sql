-- 로컬 테스트용 원천 테이블 (가짜 데이터 전용)
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS stt_call_history (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    call_id    VARCHAR(32)  NOT NULL COMMENT '통화 ID',
    seq        INT          NOT NULL COMMENT '발화 순번',
    speaker    VARCHAR(10)  NOT NULL COMMENT 'AGENT/CUSTOMER',
    utterance  TEXT         NOT NULL COMMENT 'STT 인식 결과',
    created_at DATETIME     NOT NULL,
    KEY idx_call (call_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='STT 상담 이력';

CREATE TABLE IF NOT EXISTS tts_request_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_text TEXT        NOT NULL COMMENT 'TTS 합성 요청 문장',
    channel      VARCHAR(20) NOT NULL,
    requested_at DATETIME    NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TTS 요청 이력';

-- 재시작 검증용: 기존 행을 n배로 불려 대용량을 만든다.  CALL make_bulk(30);  -> 약 10만 행
DELIMITER //
CREATE PROCEDURE IF NOT EXISTS make_bulk(IN times INT)
BEGIN
    DECLARE i INT DEFAULT 0;
    WHILE i < times DO
        INSERT INTO stt_call_history (call_id, seq, speaker, utterance, created_at)
        SELECT CONCAT(call_id, '-B', i), seq, speaker, utterance, created_at FROM stt_call_history WHERE call_id NOT LIKE '%-B%';
        SET i = i + 1;
    END WHILE;
END //
DELIMITER ;
