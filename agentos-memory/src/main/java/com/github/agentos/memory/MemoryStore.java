package com.github.agentos.memory;

import java.util.List;
import java.util.Optional;

/**
 * L0-L3 记忆数据与后台加工任务状态的统一持久化端口。
 *
 * <p>该接口隔离记忆管线与具体存储介质：调用方只依赖这里定义的幂等、版本保护、
 * 作用域过滤和任务恢复语义；实现可以使用进程内集合、本地文件或外部数据库。</p>
 *
 * <p>层级对应关系如下：</p>
 * <ul>
 *   <li>L0：已完成的原始对话轮次 {@link CompletedTurn}；</li>
 *   <li>L1：可独立检索和演进的原子记忆 {@link AtomicMemory}；</li>
 *   <li>L2：项目或任务场景摘要 {@link ScenarioMemory}；</li>
 *   <li>L3：用户与 Agent 维度的长期画像 {@link ProfileMemory}。</li>
 * </ul>
 *
 * <p>查询方法返回当前存储快照，调用方不应依赖返回集合的可变性。实现需要保证并发访问
 * 不破坏单条记录的幂等或版本约束，但是否跨进程持久化由具体实现决定。</p>
 */
public interface MemoryStore {

    /**
     * 保存一个已完成的 L0 对话轮次。
     *
     * <p>保存以 {@link CompletedTurn#id()} 为幂等键；同一标识已存在时不得重复写入或覆盖
     * 原始轮次。</p>
     *
     * @param turn 已完成的对话轮次，不能为 {@code null}
     */
    void saveTurn(CompletedTurn turn);

    /**
     * 按轮次标识查询 L0 对话轮次。
     *
     * @param turnId 轮次唯一标识
     * @return 找到时返回对应轮次，否则返回空值
     */
    Optional<CompletedTurn> findTurn(String turnId);

    /**
     * 查询指定完整会话作用域内最近完成的 L0 轮次。
     *
     * <p>最多选择最近的 {@code limit} 条记录，返回结果按完成时间从早到晚排列，便于直接
     * 作为连续对话上下文使用。{@code limit <= 0} 时返回空列表。</p>
     *
     * @param scope 团队、用户、Agent、会话及可选任务组成的记忆作用域
     * @param limit 最大返回数量
     * @return 符合作用域的轮次快照
     */
    List<CompletedTurn> listRecentTurns(MemoryScope scope, int limit);

    /**
     * 按记忆标识新增或更新一条 L1 原子记忆。
     *
     * <p>不存在同标识记录时直接新增；已存在时只有传入版本不低于当前版本才可覆盖，
     * 防止较旧的异步加工结果回写。</p>
     *
     * @param memory 待保存的原子记忆，不能为 {@code null}
     */
    void upsertAtomic(AtomicMemory memory);

    /**
     * 查询同一团队、用户和 Agent 下可被当前任务召回的 L1 原子记忆。
     *
     * <p>未绑定任务的记忆可跨任务召回；查询作用域未绑定任务时也不限制任务标识。
     * 结果按最后更新时间从新到旧排列。</p>
     *
     * @param scope 当前记忆召回作用域
     * @return 可见的原子记忆快照
     */
    List<AtomicMemory> listAtomic(MemoryScope scope);

    /**
     * 新增或更新一条 L2 场景记忆。
     *
     * <p>以场景标识为键，并使用版本号阻止旧版本覆盖新版本。</p>
     *
     * @param scenario 待保存的场景记忆，不能为 {@code null}
     */
    void saveScenario(ScenarioMemory scenario);

    /**
     * 查询当前参与者及任务场景可见的 L2 记忆。
     *
     * <p>结果按最后更新时间从新到旧排列；空任务标识可以匹配同一参与者下的跨任务场景。</p>
     *
     * @param scope 当前场景召回作用域
     * @return 可见的场景记忆快照
     */
    List<ScenarioMemory> listScenarios(MemoryScope scope);

    /**
     * 保存同一团队、用户和 Agent 维度的 L3 长期画像。
     *
     * <p>画像不以 sessionId 或 taskId 区分；只有传入版本不低于已有版本时才可更新。</p>
     *
     * @param profile 待保存的长期画像，不能为 {@code null}
     */
    void saveProfile(ProfileMemory profile);

    /**
     * 查询同一团队、用户和 Agent 维度的 L3 长期画像。
     *
     * @param scope 用于确定画像所属参与者的记忆作用域
     * @return 已保存的长期画像，不存在时返回空值
     */
    Optional<ProfileMemory> findProfile(MemoryScope scope);

    /**
     * 保存或替换一条记忆加工任务状态。
     *
     * <p>以 {@link PipelineJob#id()} 为键，用于持久化任务阶段、尝试次数和失败原因。</p>
     *
     * @param job 最新的加工任务快照，不能为 {@code null}
     */
    void saveJob(PipelineJob job);

    /**
     * 按任务标识查询记忆加工任务。
     *
     * @param jobId 加工任务唯一标识
     * @return 已保存的任务快照，不存在时返回空值
     */
    Optional<PipelineJob> findJob(String jobId);

    /**
     * 查询服务启动后可以继续执行的记忆加工任务。
     *
     * <p>结果排除已完成和尝试次数达到上限的任务，并按更新时间从早到晚排列。
     * 上次进程退出时仍为 {@link PipelineJob.Status#RUNNING} 的任务应以待处理状态返回，
     * 以便重新调度。{@code maxAttempts <= 0} 时返回空列表。</p>
     *
     * @param maxAttempts 允许恢复的最大尝试次数上限，不包含该值本身
     * @return 可重新调度的任务快照
     */
    List<PipelineJob> listRecoverableJobs(int maxAttempts);
}
