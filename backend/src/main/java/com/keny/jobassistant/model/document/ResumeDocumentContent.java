package com.keny.jobassistant.model.document;

/**
 * Tika 检测并提取后的简历文档内容。
 *
 * @param mediaType Tika 检测到的真实媒体类型
 * @param extension 后端确定的安全文件扩展名
 * @param text Tika 提取出的正文
 */
public record ResumeDocumentContent(
        String mediaType,
        String extension,
        String text
) {
}