package com.ontos.module.catalog.dal.mysql;

import java.util.List;
import java.util.Optional;

import com.ontos.module.catalog.dal.dataobject.SemanticAssetRecord;

/**
 * 语义资产草稿持久化（管理面）。
 *
 * <p>写入统一带 {@code expectedRevision}：影响行数为 0 表示「记录不存在 / 版本冲突 / 状态不符」，
 * 由 service 层再查一次做精确归因，不在 SQL 里合并语义。
 */
public interface SemanticAssetRepository {

    Optional<SemanticAssetRecord> find(String assetType, String id);

    List<SemanticAssetRecord> findAll(String assetType, String status, String keyword,
                                      int offset, int limit);

    long count(String assetType, String status, String keyword);

    void insert(SemanticAssetRecord record);

    /** 保存草稿内容：仅 status='draft' 且 revision 匹配时生效；返回影响行数。 */
    int updateDraft(SemanticAssetRecord record, long expectedRevision);

    /** 状态迁移：仅当前状态与 revision 均匹配时生效；返回影响行数。 */
    int updateStatus(String assetType, String id, String fromStatus, String toStatus,
                     long expectedRevision);

    /** 当前 revision；记录不存在返回 -1。 */
    long findRevision(String assetType, String id);
}
