package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    //根据用户账号查询用户
    Optional<User> findByUserAccount(String userAccount);
    //根据用户名进行模糊查询
    List<User> findByUsernameContaining(
            String username
    );

}
