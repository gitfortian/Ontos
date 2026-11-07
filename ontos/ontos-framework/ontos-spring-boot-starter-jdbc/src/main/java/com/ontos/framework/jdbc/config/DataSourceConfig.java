package com.ontos.framework.jdbc.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 双数据源：
 * <ul>
 *   <li>system：应用读写，承载发布清单、会话、审批、审计；</li>
 *   <li>query：查询执行器专用，只读、非 owner、必须受 RLS 约束。</li>
 * </ul>
 * 两个连接池不允许跨库复用，Query DS 的连接永远不用于写 System 表。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("ontos.datasource.system")
    public HikariDataSource systemDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("ontos.datasource.query")
    public HikariDataSource queryDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        // Query DS 连接默认只读；生产由独立只读账号 + RLS 双重约束
        dataSource.setReadOnly(true);
        return dataSource;
    }

    @Bean
    public NamedParameterJdbcTemplate systemJdbcTemplate(
            @Qualifier("systemDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public NamedParameterJdbcTemplate queryJdbcTemplate(
            @Qualifier("queryDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    /** 仅供确定性编译器渲染 SQL，不执行用户 SQL。 */
    @Bean
    public DSLContext systemDslContext(@Qualifier("systemDataSource") DataSource dataSource) {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Bean
    public DSLContext queryDslContext(@Qualifier("queryDataSource") DataSource dataSource) {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }
}
