package com.ontos.module.ontology.enums;

import com.ontos.framework.common.exception.ErrorCode;

/**
 * Ontology 模块错误码，占用 [1 001 000 000, 1 002 000 000)。
 */
public interface ErrorCodeConstants {

    ErrorCode MANIFEST_NOT_FOUND = new ErrorCode(1_001_000_000, "发布清单不存在或未加载");
    ErrorCode MANIFEST_MISMATCH = new ErrorCode(1_001_000_001, "请求清单与当前生效清单不一致: {}");
    ErrorCode ONTOLOGY_INVALID = new ErrorCode(1_001_000_002, "本体发布校验失败: {}");
    ErrorCode ASSET_NOT_FOUND = new ErrorCode(1_001_000_003, "语义资产不存在: {}");
    ErrorCode ASSET_MODE_NOT_ALLOWED = new ErrorCode(1_001_000_004, "指标不支持该分析模式: {}");
    ErrorCode DIMENSION_NOT_ALLOWED = new ErrorCode(1_001_000_005, "维度不在指标允许范围: {}");
    ErrorCode MANIFEST_HASH_MISMATCH = new ErrorCode(1_001_000_006, "发布清单内容哈希校验失败: {}");

}
