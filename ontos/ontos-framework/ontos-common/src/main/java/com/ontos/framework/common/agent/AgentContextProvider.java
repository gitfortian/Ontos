package com.ontos.framework.common.agent;

import java.util.List;

/**
 * 向 Agent 提供「当前可用候选集」的契约。
 *
 * <p>放在 common 而不是 starter-agent，是为了让业务模块实现它时不必反向依赖 Agent 框架；
 * starter-agent 只认这个接口，谁实现、候选集从哪来由装配方决定。
 *
 * <p>注入的是**白名单**语义：模型只能引用这里出现过的 ID。空实现或返回空串时，
 * 系统提示词保持原样——那意味着模型没有候选集可用，应当输出能力发现而不是编造 ID。
 */
public interface AgentContextProvider {

    /**
     * 候选集描述文本，会追加到系统提示词末尾。
     *
     * @return Markdown 文本；无可用资产时返回空串，不得返回 null
     */
    String candidateContext();

    /**
     * 候选资产词表（ID + 业务名 + 别名），供输出契约做两件事：
     * ①「只能引用候选集内 ID」的程序化校验（硬性边界 2）；
     * ②「问题是否点名了资产」的机械判定——未点名却输出执行类终态时强制澄清。
     *
     * <p>默认空列表：表示当前无候选集，两项校验都跳过（提示词已约定此时输出能力发现）。
     *
     * @return 不可变列表，不得返回 null
     */
    default List<CandidateAsset> candidateVocabulary() {
        return List.of();
    }

    /**
     * 带“当前用户问题”的候选集视图。默认退化为无问题视图；
     * 实现方可以用它解决异步修复轮拿不到请求线程 ThreadLocal 的问题。
     */
    default List<CandidateAsset> candidateVocabulary(String userQuestion) {
        return candidateVocabulary();
    }

    /**
     * 该用户问题是否由服务端注入了会话投影（多轮继承）。
     *
     * <p>「泛化问法必须澄清」的机械判定不能靠提示词文本特征推断：投影前缀会出现在模型输入里，
     * 但用户话术同样可能包含同名字符串。默认 {@code false}；实现方只在本轮确实 prime 了
     * 服务端结构化投影（上一轮已执行 DSL 的语义槽位）时返回 {@code true}。
     */
    default boolean projectionInjected(String userQuestion) {
        return false;
    }

}
