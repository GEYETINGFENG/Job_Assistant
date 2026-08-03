package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
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
        validateBasicFile(file);
        // 获取文件输入流，负责读取上传文件
        try (InputStream rawInputStream = file.getInputStream();
            BufferedInputStream inputStream = new BufferedInputStream(rawInputStream)) {
            //BufferedInputStream内部维护一个缓冲区,提高 IO 性能
            validatePdfHeader(inputStream);
            // PDFBox 需要随机访问 PDF 内容, RandomAccessReadBuffer 从 InputStream 构建读取缓冲。
            try (RandomAccessReadBuffer randomAccessRead = RandomAccessReadBuffer.createBufferFromStream(inputStream);
                 //PDF解析时，可能先读取末尾，再回来读取对象，所以需要随机访问
                 //InputStream只能从前往后，但是RandomAccessRead 可以随便跳位置
                 PDDocument document = Loader.loadPDF(randomAccessRead)) { // 复制PDF解析
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
                return extractedText.strip();//返回清理后的文本,去掉开头空格结尾空格以及换行
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Unable to parse PDF file");
        }
    }
    //校验文件的基本信息
    private void validateBasicFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume file cannot be empty");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Only PDF resume files are supported");
        }
    }


    /**
     * 校验 PDF 文件头
     * 检查完成后将输入流重置到文件开头，后续 PDFBox 可以从头读取完整文件。
     */
    private void validatePdfHeader(BufferedInputStream inputStream) throws IOException {
        inputStream.mark(PDF_HEADER.length);
        byte[] actualHeader = inputStream.readNBytes(PDF_HEADER.length);
        if (!Arrays.equals(actualHeader, PDF_HEADER)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid PDF file");
        }
        inputStream.reset(); //恢复到文件开头
    }
}