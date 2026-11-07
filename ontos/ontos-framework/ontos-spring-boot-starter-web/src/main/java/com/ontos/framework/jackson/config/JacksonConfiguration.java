package com.ontos.framework.jackson.config;

import com.ontos.framework.common.util.json.JsonUtils;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 将 Spring 管理的 Jackson 3 ObjectMapper 注入 JsonUtils，
 * 保证 MVC 序列化、JSONB 落库、审计回放使用同一套 JSON 配置。
 */
@Configuration
public class JacksonConfiguration {

    public JacksonConfiguration(ObjectMapper objectMapper) {
        JsonUtils.init(objectMapper);
    }

}
