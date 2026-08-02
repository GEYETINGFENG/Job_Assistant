package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * PDF文本提取器。
 */
@Component
public class PdfTextExtractor {
    //PDF 文件头
    private static final byte[] PDF_HEADER = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    // 最大PDF页数
    private final int maxPages;
    public PdfTextExtractor(@Value("${app.resume.ai.max-pages:30}") int maxPages) {
        this.maxPages = maxPages;
    }

    // 从PDF文件中提取文本
    public String extractText(MultipartFile file) {
        validatePdfFile(file);
        try (PDDocument document = Loader.loadPDF(file.getBytes())) { //加载 PDF 文件
            if (document.getNumberOfPages() > maxPages) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume PDF contains too many pages");
            }
            PDFTextStripper textStripper = new PDFTextStripper(); //创建文本提取器
            // 尽量按照文字在页面上的位置排序
            textStripper.setSortByPosition(true);
            String extractedText = textStripper.getText(document); //真正执行解析
            if (extractedText == null || extractedText.isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "No extractable text was found in the PDF");
            }
            return extractedText.strip(); //返回清理后的文本,去掉开头空格结尾空格以及换行
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Unable to parse PDF file");
        }
    }

    /**
     * 校验 PDF 文件。
     */
    private void validatePdfFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume file cannot be empty");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Only PDF resume files are supported");
        }
        try (InputStream inputStream = file.getInputStream()) {
            byte[] fileHeader = inputStream.readNBytes(PDF_HEADER.length);
            if (!Arrays.equals(fileHeader, PDF_HEADER)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid PDF file");
            }
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Unable to read PDF file");
        }
    }
}