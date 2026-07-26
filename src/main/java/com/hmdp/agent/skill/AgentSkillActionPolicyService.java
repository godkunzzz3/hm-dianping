package com.hmdp.agent.skill;

import org.springframework.stereotype.Component;

/**
 * Skill 层服务端动作策略。
 *
 * <p>第一版只允许 Skill 读取分析数据、检索知识和创建待确认草稿；真实写库动作必须走现有
 * Human-in-the-loop 确认接口。</p>
 */
@Component
public class AgentSkillActionPolicyService {

    public boolean isAllowed(AgentActionType actionType) {
        if (actionType == null) {
            return false;
        }
        return actionType == AgentActionType.READ_ANALYTICS
                || actionType == AgentActionType.RETRIEVE_KNOWLEDGE
                || actionType == AgentActionType.CREATE_DRAFT;
    }

    public SkillResult<Void> requireAllowed(AgentActionType actionType) {
        if (isAllowed(actionType)) {
            return SkillResult.success(null);
        }
        return SkillResult.failure("ACTION_NOT_ALLOWED", "Skill 不允许执行动作：" + actionType);
    }
}
