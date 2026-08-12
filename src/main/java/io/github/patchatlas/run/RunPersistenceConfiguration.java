package io.github.patchatlas.run;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 持久化装配：配置了 {@code spring.datasource.url} 时创建 {@link PostgresRunStore}。
 *
 * <p>推荐：{@code SPRING_PROFILES_ACTIVE=persistence} + {@code SPRING_DATASOURCE_*}。
 * Flyway 由 Boot 在 persistence profile 下自动迁移。
 *
 * <p>注意：不用 {@code @ConditionalOnBean(DataSource)} 作类级条件——扫描阶段 DataSource
 * 尚未注册会导致条件误判为 false。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class RunPersistenceConfiguration {

    @Bean
    PostgresRunStore postgresRunStore(DataSource dataSource) {
        return new PostgresRunStore(dataSource);
    }
}
