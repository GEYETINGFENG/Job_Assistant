package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository
        extends JpaRepository<User, Long> {

    Optional<User> findByUserAccount(String userAccount);

    Optional<User> findByUserAccountAndUserPassword(
            String userAccount,
            String userPassword
    );
    List<User> findByUsernameContaining(
            String username
    );

}
