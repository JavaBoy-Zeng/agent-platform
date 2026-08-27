package com.github.agentos.server.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 登录用户的持久化端口；实现方保证 username 唯一。 */
public interface UserStore {

    /** 按用户名查找；不存在返回 empty。 */
    Optional<UserAccount> findByUsername(String username);

    /** 全量用户列表，按创建时间升序。 */
    List<UserAccount> list();

    /** 新建用户；用户名已存在时抛出 IllegalStateException。 */
    void create(UserAccount account);

    /** 更新密码哈希；用户不存在返回 false。 */
    boolean updatePasswordHash(String username, String passwordHash);

    /** 完整替换用户角色；用户不存在返回 false。 */
    boolean updateRoles(String username, Set<String> roles);

    /** 删除用户；用户不存在返回 false。 */
    boolean delete(String username);

    /** 当前用户总数。 */
    long count();
}
