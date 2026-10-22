package com.piimask.app.connection;

public final class ConnectionDtos {

    private ConnectionDtos() {
    }

    /** 등록·수정 요청. 수정 시 password 가 비어 있으면 기존 비밀번호 유지 */
    public record ConnectionRequest(String name, String host, Integer port, String database,
                                    String username, String password, String options) {
    }

    /** 화면 응답. 비밀번호는 내려주지 않는다 */
    public record ConnectionView(long id, String name, String host, int port, String database,
                                 String username, String options, boolean hasPassword,
                                 String createdAt, String updatedAt) {
    }

    public record TestResult(boolean ok, String message, String serverVersion, long elapsedMs) {
    }

    public record TableInfo(String name, long approxRows, String comment) {
    }

    public record ColumnInfo(String name, String dataType, String columnType, boolean nullable,
                             boolean primaryKey, boolean textLike, String comment) {
    }

    /** 내부용: 복호화된 접속 정보 */
    public record ConnectionSecret(long id, String name, String host, int port, String database,
                                   String username, String password, String options) {

        public String jdbcUrl() {
            String base = "jdbc:mariadb://" + host + ":" + port + "/" + database;
            return options == null || options.isBlank() ? base : base + "?" + options;
        }
    }
}
