package com.hmdp.agent.skill;

/**
 * Agent Skill 可表达的服务端动作类型。
 *
 * <p>文本风险识别只能作为提示信号，真正授权必须落到明确动作类型上。</p>
 */
public enum AgentActionType {

    READ_ANALYTICS,

    RETRIEVE_KNOWLEDGE,

    CREATE_DRAFT,

    UPDATE_DRAFT,

    CONFIRM_DRAFT,

    CREATE_VOUCHER,

    CHANGE_STOCK,

    REFUND
}
