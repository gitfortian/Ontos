package com.ontos.spi.datasource;

/**
 * 插件侧统一异常（B2）：按操作阶段分类，平台据此映射业务错误码。
 *
 * <p>异常消息不得包含密码、token、private key 等 secret；本类不携带 cause 之外的任何
 * 连接细节，避免敏感信息经异常链外泄。
 */
public class DataSourcePluginException extends RuntimeException {

    public enum Operation {
        /** 配置解析 / 参数校验失败。 */
        PARAMETER,
        /** 连接不可达或认证失败。 */
        CONNECTIVITY,
        /** 元数据读取失败。 */
        CATALOG
    }

    private final Operation operation;

    public DataSourcePluginException(Operation operation, String message) {
        super(message);
        this.operation = java.util.Objects.requireNonNull(operation, "operation");
    }

    public DataSourcePluginException(Operation operation, String message, Throwable cause) {
        super(message, cause);
        this.operation = java.util.Objects.requireNonNull(operation, "operation");
    }

    public Operation operation() {
        return operation;
    }
}
