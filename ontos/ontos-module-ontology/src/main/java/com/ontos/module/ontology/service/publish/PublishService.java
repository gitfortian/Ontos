package com.ontos.module.ontology.service.publish;

import java.util.List;
import java.util.Map;

import com.ontos.module.ontology.core.ManifestPayload;

/**
 * 发布服务：清单发布预检（dry-run）与确认发布。
 *
 * <p>发布流程对齐技术选型 §4.2：Git 为唯一真源，本端点接收已提交的载荷做
 * 「预检 → 确认」两段式发布；管理界面只提交变更单，不直写运行时表。
 * content_hash 由数据库按 jsonb 规范文本计算，发布后不自动激活（激活走 T3.3 重载命令）。
 */
public interface PublishService {

    /**
     * 发布（dry-run 仅预检，不写清单）。发布人身份与策略闸门由 {@link PublishGuard} 解析
     * （实现位于装配点，保持本模块不依赖 web / system）。
     *
     * @param request manifestId / sourceRef / dryRun / payload
     * @return 预检报告：valid=true 且 dryRun=false 时已落库并返回 content_hash
     */
    PublishReportVO publish(PublishRequest request);

    /** 发布历史（管理端展示，含 dry-run 预检记录）。 */
    java.util.List<Map<String, Object>> listPublishRecords(int limit);

    /**
     * 发布请求体。payload 为原始 JSON 树（Map 形态）：发布服务先做「未知字段检测」
     * （提交树 vs 类型化回装树键差分，拼写错误的字段不再被静默丢弃），
     * 再转换为类型化载荷走校验与发布。
     */
    record PublishRequest(String manifestId, String sourceRef, boolean dryRun, Map<String, Object> payload) {
    }

    /** 预检/发布结果。 */
    record PublishReportVO(String manifestId, boolean valid, List<String> violations,
                           boolean alreadyExists, String contentHash,
                           Map<String, Integer> assetCounts) {
    }

}
