package com.keny.jobassistant.service.impl;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.service.ResumeFileStorageService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地简历文件存储服务实现类。
 * PDF文件保存到服务器本地目录，
 * 文件名由后端根据简历 ID 生成，不使用客户端上传的原始文件名。
 */
@Service
public class ResumeFileStorageServiceImpl implements ResumeFileStorageService {
    private static final String PDF_EXTENSION = ".pdf";
    private static final String DOCX_EXTENSION = ".docx";
    //简历文件存储根目录
    private final Path storageRoot;
    public ResumeFileStorageServiceImpl(
            @Value("${app.resume.storage-directory:./data/resumes}")
            String storageDirectory) {
        // 这里先把字符串对象变成Path对象，然后转换成绝对路径，最后规范化路径
        this.storageRoot = Paths.get(storageDirectory).toAbsolutePath().normalize();
    }

    //项目启动时创建简历文件存储目录
    @PostConstruct
    //Spring 创建完这个 Bean 后，自动执行这个方法一次
    public void initializeStorageDirectory() {
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to initialize resume storage directory", exception);
        }
    }

    //保存简历文件
    @Override
    public String storeResumeFile(Long resumeId, MultipartFile file, String extension) {
        validateResumeId(resumeId);
        validateStorageExtension(extension);
        // 防止同一个 ID 遗留 PDF 和 DOCX
        deleteResumeFile(resumeId);
        Path targetPath = resolveFilePath(resumeId, extension);// 获取保存路径
        try (InputStream inputStream = file.getInputStream()) { // 从上传文件里面读取数据
            //把上传文件复制到服务器,三个参数分别是用户上传文件，服务器保存位置以及如果目标文件存在，覆盖
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to store resume file");
        }
        // 返回的是API访问地址，有一个API接口来获取文件
        return "/resumes/" + resumeId + "/file";
    }

    /**
     * 根据简历 ID 找到服务器中保存的文件，
     * 把它转换成 Spring可以返回给前端下载的 Resource对象。
     */
    @Override
    public Resource loadResumeFile(Long resumeId) {
        validateResumeId(resumeId);
        Path pdfPath = resolveFilePath(resumeId, PDF_EXTENSION);
        Path docxPath = resolveFilePath(resumeId, DOCX_EXTENSION);
        Path existingPath;
        if (Files.isRegularFile(pdfPath)) {//判断这个路径有没有对应文件
            existingPath = pdfPath;
        } else if (Files.isRegularFile(docxPath)) {
            existingPath = docxPath;
        } else {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Resume file does not exist");
        }
        try {
            // 把普通文件路径转换成 URI 格式
            Resource resource = new UrlResource(existingPath.toUri());
            if (!resource.isReadable()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Resume file is not readable");
            }
            return resource;
        } catch (MalformedURLException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to load resume file");
        }
    }

    /**
     * 删除简历文件。
     */
    @Override
    public void deleteResumeFile(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            return;
        }
        try {
            Files.deleteIfExists(resolveFilePath(resumeId, PDF_EXTENSION));
            Files.deleteIfExists(resolveFilePath(resumeId, DOCX_EXTENSION));
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to delete resume file");
        }
    }

    /**
     * 根据简历 ID 生成服务器上实际保存 PDF 文件的路径，并且防止路径穿越攻击。
     */
    private Path resolveFilePath(Long resumeId,String extension) {
        // 拼接文件路径
        Path filePath = storageRoot.resolve("resume-" + resumeId + extension).normalize();
        // 防止路径穿越
        if (!filePath.startsWith(storageRoot)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid resume file path");
        }
        return filePath;
    }

    private void validateStorageExtension(String extension) {
        if (!PDF_EXTENSION.equals(extension) && !DOCX_EXTENSION.equals(extension)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Unsupported resume file extension");
        }
    }


    // 校验简历 ID
    private void validateResumeId(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid resume ID");
        }
    }
}