package com.ontos.module.session.service.retrieval;

/**
 * 检索瀑布产出的单个候选资产提及。
 *
 * <p>{@code layer} 与 {@code fuzzy} 共同构成「模糊候选进 DSL=0」硬断言的判定基础：
 * 只有 {@code fuzzy=false} 的候选（L1/L2）允许进入 DSL 组装输入；
 * {@code fuzzy=true}（L3 受限模糊）只能出现在澄清候选里。
 *
 * @param assetId     资产 ID（metric.x / dim.x / concept.x / property.x）
 * @param assetType   资产类型（快照 wire 形态：metric / dimension / property / entity）
 * @param matchedTerm 命中的词（业务名 / 别名 / 跨资产补充词）
 * @param layer       命中层级：1=精确（全等）、2=词典（子串包含）、3=受限模糊
 * @param fuzzy       是否模糊命中（等价于 layer==3；显式字段供断言与契约校验直读）
 * @param score       排序得分：L1=100；L2=60+词长；L3=30+词长-距离惩罚
 */
public record RetrievedCandidate(String assetId, String assetType, String matchedTerm,
                                 int layer, boolean fuzzy, int score)
        implements Comparable<RetrievedCandidate> {

    @Override
    public int compareTo(RetrievedCandidate other) {
        // 层级高（数字小）者在前：L1 精确 > L2 词典 > L3 模糊；同层按得分降序；
        // 同分按词长（更具体的词）在前；最后按 ID 稳定排序
        if (this.layer != other.layer) {
            return Integer.compare(this.layer, other.layer);
        }
        if (this.score != other.score) {
            return Integer.compare(other.score, this.score);
        }
        int byTerm = Integer.compare(other.matchedTerm.length(), this.matchedTerm.length());
        if (byTerm != 0) {
            return byTerm;
        }
        return this.assetId.compareTo(other.assetId);
    }

}
