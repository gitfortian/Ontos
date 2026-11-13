package com.ontos.module.datasource.enums;

import com.ontos.framework.common.exception.ErrorCode;

/**
 * 数据源模块错误码（B2）：1_006 段。
 *
 * <p>注：AGENTS.md §3 目前只登记了 1_001 / 1_002 / 1_003，1_005（catalog）与 1_006（datasource）
 * 均为既成事实，AGENTS.md 的分段描述属既有欠账，待统一升版修订。
 */
public interface ErrorCodeConstants {

    ErrorCode DATASOURCE_PLUGIN_NOT_FOUND = new ErrorCode(1_006_000_101, "数据源插件未注册: {}");

    ErrorCode DATASOURCE_CONFIG_INVALID = new ErrorCode(1_006_000_102, "数据源连接配置非法: {}");

    ErrorCode DATASOURCE_CONNECT_FAILED = new ErrorCode(1_006_000_103, "数据源连接失败: {}");

    ErrorCode DATASOURCE_CATALOG_FAILED = new ErrorCode(1_006_000_104, "数据源元数据读取失败: {}");

    ErrorCode DATASOURCE_TYPE_INVALID = new ErrorCode(1_006_000_105, "数据源类型非法: {}");

    ErrorCode DATASOURCE_NOT_CONFIGURED = new ErrorCode(1_006_000_106, "当前查询数据源未配置");
}
