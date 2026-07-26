package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.model.dto.UserDTO;
import com.keny.jobassistant.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin")
public class AdminController {
    @Resource
    private UserService userService;

    @GetMapping("/users")
    public BaseResponse<List<UserDTO>> searchUser(String username){
        List<UserDTO> userList = userService.searchUser(username);
        return ResultUtils.success(userList);
    }

    /**
     * Only ADMIN can delete users
     */
    @DeleteMapping("/users/{id}")
    public BaseResponse<Boolean> deleteUser(@PathVariable Long id){
        boolean result = userService.deleteUser(id);
        return ResultUtils.success(result);
    }
}
