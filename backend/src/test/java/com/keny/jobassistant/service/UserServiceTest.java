package com.keny.jobassistant.service;

import com.keny.jobassistant.exception.BusinessException;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 用户服务测试
 *
 * @author keny
 **/

@SpringBootTest
class UserServiceTest {


    @Resource
    private UserService userService;


    /**
     * 测试正常注册
     */
    @Test
    void userRegisterSuccess() {

        String userAccount = "TestKeny001";
        String userPassword = "12345678";
        String checkPassword = "12345678";
        long result = userService.userRegister(
                userAccount,
                userPassword,
                checkPassword
        );
        Assertions.assertTrue(result > 0);
    }



    /**
     * 测试账号为空
     */
    @Test
    void userRegisterEmptyAccount(){
        String userAccount = "";
        String userPassword = "12345678";
        String checkPassword = "12345678";

        Assertions.assertThrows(
                BusinessException.class,
                () -> userService.userRegister(
                        userAccount,
                        userPassword,
                        checkPassword
                )
        );
    }




    /**
     * 测试密码长度不足
     */
    @Test
    void userRegisterShortPassword(){
        String userAccount = "TestKeny002";
        String userPassword = "123";
        String checkPassword = "123";

        Assertions.assertThrows(
                BusinessException.class,
                () -> userService.userRegister(
                        userAccount,
                        userPassword,
                        checkPassword
                )
        );

    }

    /**
     * 测试两次密码不一致
     */
    @Test
    void userRegisterPasswordNotSame(){
        String userAccount = "TestKeny003";
        String userPassword = "12345678";
        String checkPassword = "87654321";
        Assertions.assertThrows(
                BusinessException.class,
                () -> userService.userRegister(
                        userAccount,
                        userPassword,
                        checkPassword
                )
        );

    }

    /**
     * 测试账号包含特殊字符
     */
    @Test
    void userRegisterSpecialCharacter(){

        String userAccount = "Test@123";
        String userPassword = "12345678";
        String checkPassword = "12345678";

        long result = userService.userRegister(
                userAccount,
                userPassword,
                checkPassword
        );

        Assertions.assertEquals(-1,result);

    }



    /**
     * 测试重复注册
     */
    @Test
    void userRegisterDuplicate(){


        String userAccount = "TestDuplicate";
        String password = "12345678";


        long first =
                userService.userRegister(
                        userAccount,
                        password,
                        password
                );


        Assertions.assertTrue(first>0);
        long second =
                userService.userRegister(
                        userAccount,
                        password,
                        password
                );
        Assertions.assertEquals(-1,second);

    }


}