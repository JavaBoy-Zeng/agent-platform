package com.github.agentos.memory;

/** 一条长期记忆当前是否仍可参与 Agent 召回。 */
public enum MemoryStatus {
    /** 正常且可召回。 */
    ACTIVE,
    /** 已被人工或系统判定为不再可信，不参与召回但保留审计记录。 */
    INVALIDATED,
    /** 已被更新的记忆替代，不参与召回并通过 supersededById 指向新版本。 */
    SUPERSEDED
}
