package com.keny.jobassistant.service;

import com.keny.jobassistant.model.domain.User;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 用户服务测试
 *
 * @author keny
 **/
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class UserServiceTest {
    @Resource
    private UserService userService;

    @Test
    public void testAddUser() {
        User user = new User();//接下来要给对象设置值
        user.setUsername("TestKeny");
        user.setUserAccount("aaa");
        user.setAvatarUrl("1111");
        user.setGender(0);
        user.setUserPassword("123456");

        boolean result = userService.save(user);
        System.out.println(user.getId());
        assertTrue(result);

    }

    @Test
    void userRegister() {
        //校验密码非空
        String userAccount = "Yupi";
        String userPassword = "";
        String checkPassword = "123456";
        long result = userService.userRegister(userAccount, userPassword, checkPassword);
        Assertions.assertEquals(-1, result);
        //校验账户不小于四位
        userAccount = "yu";
        result = userService.userRegister(userAccount, userPassword, checkPassword);
        Assertions.assertEquals(-1, result);
        //校验密码不小于八位
        userAccount = "yupi";
        userPassword = "123456";
        result = userService.userRegister(userAccount, userPassword, checkPassword);
        Assertions.assertEquals(-1, result);
        //校验账户不能包含特殊字符
        userAccount = "yu pi";
        userPassword = "12345678";
        result = userService.userRegister(userAccount, userPassword, checkPassword);
        Assertions.assertEquals(-1, result);
        //校验密码和校验密码相同
        checkPassword = "123456789";
        result = userService.userRegister(userAccount, userPassword, checkPassword);
        Assertions.assertEquals(-1, result);
        //账户名不能重复
        userAccount = "dogYupi";//数据库里面的
        userPassword = "12345678";
        result = userService.userRegister(userAccount, userPassword, checkPassword);
        Assertions.assertEquals(-1, result);
        //真正执行成功
        userAccount = "Yupi";
        checkPassword = "12345678";
        result = userService.userRegister(userAccount, userPassword, checkPassword);
        Assertions.assertTrue(result>0);

    }
}