package com.keny.jobassistant.service;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

//简历文件存储服务
public interface ResumeFileStorageService {

    /**
     * 保存简历文件，并返回后端生成的文件地址。
     * @param resumeId 简历 ID
     * @param file 客户端上传的简历文件
     * @param extension 根据真实类型生成的扩展名
     * @return 文件存储地址
     */
    String storeResumeFile(Long resumeId,MultipartFile file, String extension);

    /**
     * 加载指定简历的 PDF 文件。
     * @param resumeId 简历 ID
     * @return PDF 文件资源
     */
    Resource loadResumeFile(Long resumeId);

    /**
     * 删除指定简历的 PDF 文件。
     * @param resumeId 简历 ID
     */
    void deleteResumeFile(Long resumeId);
}