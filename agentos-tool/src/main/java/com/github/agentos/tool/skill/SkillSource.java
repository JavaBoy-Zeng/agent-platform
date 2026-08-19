package com.github.agentos.tool.skill;

import java.io.IOException;
import java.util.List;

/**
 * 技能来源协议。
 *
 * <p>本地目录、classpath 资源等来源实现该接口，向注册表提供解析完成的
 * {@link AgentSkill} 集合。来源只负责发现与解析，不负责注册。</p>
 */
public interface SkillSource {

    /**
     * 加载该来源下的全部技能。
     *
     * @return 解析完成的技能列表；来源为空时返回空列表
     * @throws IOException 当来源不可读或内容格式非法时抛出
     */
    List<AgentSkill> load() throws IOException;
}
