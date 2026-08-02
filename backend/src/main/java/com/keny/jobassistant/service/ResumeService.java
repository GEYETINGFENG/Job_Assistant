package com.keny.jobassistant.service;
import com.keny.jobassistant.model.dto.ResumeDTO;
import com.keny.jobassistant.model.entity.request.ResumeCreateRequest;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历服务接口。
 */
public interface ResumeService {

    /**
     * 为当前登录用户创建简历。
     *
     * @param request 创建简历请求
     * @param file 客户端上传的简历文件
     * @return 新创建的简历 ID
     */
    Long createResume(ResumeCreateRequest request, MultipartFile file);

    /**
     * 查询当前登录用户拥有的指定简历。
     *
     * @param resumeId 简历 ID
     * @return 简历信息
     */
    ResumeDTO getResume(Long resumeId);
}