package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    //判断用户账号是否已经存在,已经被逻辑删除的账号也会被统计。
    boolean existsByUserAccount(String userAccount);
    //根据用户账号和逻辑删除状态查询用户
    Optional<User> findByUserAccountAndIsDelete(String userAccount, Integer isDelete);
    //根据用户名进行模糊查询，并根据逻辑删除状态过滤用户
    List<User> findByUsernameContainingAndIsDelete(String username, Integer isDelete);

    //根据逻辑删除状态查询所有用户
    List<User> findAllByIsDelete(Integer isDelete);

    //根据用户 ID，把未删除用户的 isDelete 从 0 更新为 1，并刷新 updateTime，从而实现逻辑删除
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.isDelete = 1, u.updateTime = CURRENT_TIMESTAMP WHERE u.id = :id AND u.isDelete = 0")
    int softDeleteById(@Param("id") Long id);

}
