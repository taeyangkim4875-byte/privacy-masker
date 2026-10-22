package com.piimask.app.batch;

import com.piimask.app.support.Errors;

/**
 * 배치 단계에서 밖으로 내보내는 유일한 예외.
 * Spring Batch 는 실패 예외의 스택 트레이스를 ERROR 로그와 메타 DB(EXIT_MESSAGE)에 남기므로,
 * 원인 예외를 붙이지 않고 메시지도 유형·SQLState 만 담는다({@link Errors#codeOnly}).
 * 화면 실행 이력에는 기존과 같은 {@link Errors#safeMessage} 를 쓰도록 별도 필드로 들고 간다.
 */
public class SanitizedBatchException extends RuntimeException {

    private final transient String historyMessage;

    public SanitizedBatchException(Throwable cause) {
        super(Errors.codeOnly(cause), null, false, true);
        this.historyMessage = Errors.safeMessage(cause);
    }

    /** job_execution.error_message 용(기존 형식) */
    String historyMessage() {
        return historyMessage;
    }

    /** 이미 SanitizedBatchException 이면 그대로, 아니면 감싸서(원인 없이) 돌려준다 */
    static SanitizedBatchException of(Throwable e) {
        return e instanceof SanitizedBatchException s ? s : new SanitizedBatchException(e);
    }
}
