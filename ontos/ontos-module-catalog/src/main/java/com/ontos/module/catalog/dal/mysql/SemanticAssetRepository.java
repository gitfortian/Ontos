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

    /** 跨类型按状态取全部资产（合成发布用）；按 asset_type, id 排序保证批次稳定。 */
    List<SemanticAssetRecord> findAllByStatus(String status);

    /**
     * 跨类型取「待发布批次」= draft + reviewing（草稿合并视图用）；
     * 提交阶段的跨草稿引用（新对象 + 挂在其上的属性）据此互相可见。
     */
    List<SemanticAssetRecord> findAllPending();

    /** 保存草稿内容：仅 status='draft' 且 revision 匹配时生效；返回影响行数。 */
    int updateDraft(SemanticAssetRecord record, long expectedRevision);

    /** 状态迁移：仅当前状态与 revision 均匹配时生效；返回影响行数。 */
    int updateStatus(String assetType, String id, String fromStatus, String toStatus,
                     long expectedRevision);

    /**
     * 合成发布的落定（B1/P1-B）：仅 status='reviewing' 且 revision 匹配时迁移到 published，
     * 并写 published_at（满足 V21 的 ck_sad_published_at_required 约束）；返回影响行数。
     */
    int markPublished(String assetType, String id, long expectedRevision);

    /**
     * 修订已生效资产（B1/P1-B UI 补齐）：仅 status IN ('published','retired') 且 revision 匹配时
     * 回迁 draft，definition/name/summary 以生效清单为准并清空 published_at
     * （满足 V21 的 ck_sad_published_at 约束）；返回影响行数。
     */
    int reviseToDraft(String assetType, String id, String definitionJson, String name,
                      String summary, long expectedRevision);

    /** 当前 revision；记录不存在返回 -1。 */
    long findRevision(String assetType, String id);
}
