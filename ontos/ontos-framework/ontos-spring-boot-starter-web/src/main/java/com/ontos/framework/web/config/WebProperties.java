package com.ontos.framework.web.config;

import java.util.List;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web 层配置：跨域与本地开发身份头开关。
 */
@Data
@ConfigurationProperties(prefix = "ontos.web")
public class WebProperties {

    private Cors cors = new Cors();
    private Security security = new Security();

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");
    }

    @Data
    public static class Security {
        /**
         * 仅限本地开发：允许缺省身份头，回退为 demo 租户/用户。
         * 生产必须关闭，由网关校验并覆写 X-Ontos-* 头。
         */
        private boolean devHeaders = false;
    }
}
