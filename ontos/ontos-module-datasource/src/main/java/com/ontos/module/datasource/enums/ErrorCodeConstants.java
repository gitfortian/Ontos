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

    // —— 注册配置（B2 Phase 2 前半，ADR-0025 凭据加密）——
    ErrorCode DATASOURCE_CONFIG_NOT_FOUND = new ErrorCode(1_006_000_107, "数据源配置不存在: {}");
    ErrorCode DATASOURCE_NAME_DUPLICATE = new ErrorCode(1_006_000_108, "数据源名称已存在: {}");
    ErrorCode DATASOURCE_MASTER_KEY_MISSING =
            new ErrorCode(1_006_000_109, "数据源凭据主密钥未配置（ontos.datasource.master-key）");
    ErrorCode DATASOURCE_SECRET_CIPHER_FAILED = new ErrorCode(1_006_000_110, "数据源凭据加解密失败: {}");

    // —— 数据视图资产（B3）——
    ErrorCode DATASOURCE_VIEW_NOT_FOUND = new ErrorCode(1_006_000_111, "数据视图不存在: {}");
    ErrorCode DATASOURCE_VIEW_REFERENCED =
            new ErrorCode(1_006_000_112, "数据视图仍被生效清单引用，无法退役: {}");
    ErrorCode DATASOURCE_VIEW_ID_DUPLICATE = new ErrorCode(1_006_000_113, "数据视图 ID 已存在: {}");
    ErrorCode DATASOURCE_VIEW_REACTIVATE_CONFLICT =
            new ErrorCode(1_006_000_114, "同源同表已存在 active 数据视图: {}");
}
