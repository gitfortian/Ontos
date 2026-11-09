package com.ontos.module.session.dal.dataobject;

import lombok.Data;

/**
 * 会话权威状态行（V1 表 + V14 扩列；M4 收口审核 F-1 投影模型）。
 */
@Data
public class SessionStateDO {

    private String tenantId;
    private String userId;
    private String sessionId;
    private Integer turnIndex;
    /** 本轮已采纳 DSL 的实体投影（全量覆盖）；JSON 文本。 */
    private String resolvedEntities;
    /** 最近 N 轮上下文（turn/terminal/投影摘要/requestId）；JSON 数组文本。 */
    private String recentTurns;
    /** 业务场景状态（M5 仅 free 态骨架）。 */
    private String scenarioState;
    private String lastTerminal;
    /** 未答复的澄清候选（四要素 + candidates）；JSON 文本；null=无待答澄清。 */
    private String pendingClarification;
    private String updatedAt;
}
