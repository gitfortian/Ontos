package com.ontos.module.catalog.enums;

import com.ontos.framework.common.exception.ErrorCode;

/**
 * Catalog 模块错误码，占用 [1 005 000 000, 1 006 000 000)。
 */
public interface ErrorCodeConstants {

    ErrorCode SCENARIO_NOT_FOUND = new ErrorCode(1_005_000_001, "Scenario 不存在: {}");
    ErrorCode SCENARIO_ALREADY_EXISTS = new ErrorCode(1_005_000_002, "Scenario 已存在: {}");
    ErrorCode SCENARIO_STATE_INVALID = new ErrorCode(1_005_000_003, "Scenario 状态迁移非法: {} -> {}");
    ErrorCode SCENARIO_REVIEW_REQUIRED = new ErrorCode(1_005_000_004, "Scenario 未经人工审核，禁止发布: {}");
    ErrorCode SCENARIO_INVALID = new ErrorCode(1_005_000_005, "Scenario 发布校验失败: {}");
    ErrorCode SCENARIO_MANIFEST_MISSING = new ErrorCode(1_005_000_006, "没有生效清单，Scenario 无法发布");
    ErrorCode SCENARIO_DEPENDENCY_BLOCKED = new ErrorCode(1_005_000_007, "Scenario 依赖的资产已失效: {}");
    ErrorCode SCENARIO_VERSION_INVALID = new ErrorCode(1_005_000_008, "Scenario 版本号非法: {}");

    // ========== B1 语义资产草稿（1_005_000_101 起，避开已发布的 001–008） ==========

    ErrorCode ASSET_NOT_FOUND = new ErrorCode(1_005_000_101, "语义资产不存在: {}");
    ErrorCode ASSET_ALREADY_EXISTS = new ErrorCode(1_005_000_102, "语义资产已存在: {}");
    ErrorCode ASSET_STATE_INVALID = new ErrorCode(1_005_000_103, "语义资产状态迁移非法: {} -> {}");
    ErrorCode ASSET_REVISION_CONFLICT = new ErrorCode(1_005_000_104, "语义资产版本冲突（期望 {}，当前 {}）");
    ErrorCode ASSET_TYPE_INVALID = new ErrorCode(1_005_000_105, "语义资产类型非法: {}");
    ErrorCode ASSET_VALIDATION_FAILED = new ErrorCode(1_005_000_106, "语义资产校验未通过: {}");
    ErrorCode ASSET_DEFINITION_INVALID = new ErrorCode(1_005_000_107, "语义资产定义非法: {}");
    ErrorCode ASSET_ID_IMMUTABLE = new ErrorCode(1_005_000_108, "语义资产 ID 不可修改: {}");
}
