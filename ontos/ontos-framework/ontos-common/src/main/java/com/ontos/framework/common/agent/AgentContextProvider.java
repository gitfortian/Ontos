package com.ontos.framework.common.agent;

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

}
