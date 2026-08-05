package com.keny.jobassistant.service.support;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 基于本地临时文件的 MultipartFile 适配器。
 *
 * 作用：
 * 让 S3 下载后的临时文件继续复用现有 ResumeParserService，
 * 不需要修改 Tika 和 AI 解析接口。
 *
 * 解析过程仍然通过 InputStream 读取，不使用 getBytes()。
 */
public class PathBackedMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final Path path;

    public PathBackedMultipartFile(String name, String originalFilename, String contentType, Path path) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.path = path;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return getSize() == 0;
    }

    @Override
    public long getSize() {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read temporary file size", exception);
        }
    }

    /**
     * 明确禁止使用 getBytes()，防止未来代码重新把完整文件读入 byte[]。
     */
    @Override
    public byte[] getBytes() {
        throw new UnsupportedOperationException("Use getInputStream() instead of getBytes()");
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public void transferTo(File destination) throws IOException {
        Files.copy(path, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}