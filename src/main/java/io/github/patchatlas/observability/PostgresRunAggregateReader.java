package io.github.patchatlas.observability;

import io.github.patchatlas.replay.ReplayVerdict;
import io.github.patchatlas.replay.VerificationMode;
import io.github.patchatlas.run.FailureCategory;
import io.github.patchatlas.run.FailureStage;
import io.github.patchatlas.run.RecordedUsageStatus;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 只读聚合 SQL；失败向上抛出，不返回零或缓存。 */
public final class PostgresRunAggregateReader implements RunAggregateReader {

    private final JdbcClient jdbc;

    public PostgresRunAggregateReader(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    }

    @Override
    public long completedRuns(VerificationMode mode, ReplayVerdict verdict) {
        return queryLong(
                """
                SELECT COUNT(*)
                  FROM verification_run
                 WHERE state = 'COMPLETED'
                   AND mode = :mode
                   AND verdict = :verdict
                """,
                "mode",
                mode.name(),
                "verdict",
                verdict.name());
    }

    @Override
    public long failedRuns(VerificationMode mode, FailureStage stage, FailureCategory category) {
        return jdbc.sql(
                        """
                        SELECT COUNT(*)
                          FROM verification_run
                         WHERE state = 'FAILED'
                           AND mode = :mode
                           AND failure_stage = :stage
                           AND failure_category = :category
                        """)
                .param("mode", mode.name())
                .param("stage", stage.name())
                .param("category", category.name())
                .query(Number.class)
                .single()
                .longValue();
    }

    @Override
    public long generationAttempts(String provider) {
        return queryLong(
                """
                SELECT COALESCE(SUM(generation_attempt_count), 0)
                  FROM verification_run
                 WHERE model_provider = :provider
                """,
                "provider",
                provider);
    }

    @Override
    public long usageRecords(String provider) {
        return queryLong(
                """
                SELECT COALESCE(SUM(model_usage_record_count), 0)
                  FROM verification_run
                 WHERE model_provider = :provider
                   AND model_usage_record_count IS NOT NULL
                """,
                "provider",
                provider);
    }

    @Override
    public long usageRuns(String provider, RecordedUsageStatus status) {
        String predicate =
                switch (status) {
                    case TRACKING_UNAVAILABLE -> "model_usage_record_count IS NULL";
                    case NONE_RECORDED -> "model_usage_record_count = 0";
                    case PARTIALLY_RECORDED ->
                            "model_usage_record_count > 0 AND model_usage_record_count < generation_attempt_count";
                    case RECORDED_FOR_ALL_ATTEMPTS ->
                            "model_usage_record_count = generation_attempt_count AND generation_attempt_count > 0";
                };
        return jdbc.sql(
                        """
                        SELECT COUNT(*)
                          FROM verification_run
                         WHERE generation_attempt_count > 0
                           AND model_provider = :provider
                           AND %s
                        """
                                .formatted(predicate))
                .param("provider", provider)
                .query(Number.class)
                .single()
                .longValue();
    }

    @Override
    public long tokens(String provider, String type) {
        return queryLong(
                "SELECT COALESCE(SUM(" + tokenColumn(type) + "), 0) FROM verification_run WHERE model_provider = :provider",
                "provider",
                provider);
    }

    @Override
    public long tokensForModel(String provider, String model, String type) {
        return jdbc.sql(
                        "SELECT COALESCE(SUM("
                                + tokenColumn(type)
                                + "), 0) FROM verification_run WHERE model_provider = :provider AND model_name = :model")
                .param("provider", provider)
                .param("model", model)
                .query(Number.class)
                .single()
                .longValue();
    }

    @Override
    public TokenSnapshot tokensForModelSnapshot(String provider, String model) {
        return jdbc.sql(
                        """
                        SELECT
                          COALESCE(SUM(model_input_tokens), 0),
                          COALESCE(SUM(model_output_tokens), 0),
                          COALESCE(SUM(model_total_tokens), 0)
                          FROM verification_run
                         WHERE model_provider = :provider AND model_name = :model
                        """)
                .param("provider", provider)
                .param("model", model)
                .query((rs, rowNum) -> new TokenSnapshot(
                        rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .single();
    }

    private long queryLong(String sql, String key, String value) {
        return jdbc.sql(sql).param(key, value).query(Number.class).single().longValue();
    }

    private long queryLong(String sql, String k1, String v1, String k2, String v2) {
        return jdbc.sql(sql).param(k1, v1).param(k2, v2).query(Number.class).single().longValue();
    }

    private static String tokenColumn(String type) {
        return switch (type) {
            case "input" -> "model_input_tokens";
            case "output" -> "model_output_tokens";
            case "total" -> "model_total_tokens";
            default -> throw new IllegalArgumentException("unknown token type: " + type);
        };
    }
}
