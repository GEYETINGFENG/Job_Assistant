package com.keny.jobassistant.service;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.document.ResumeDocumentContent;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tika 简历文档处理器。
 * 处理流程：
 * 1. 使用 Tika 检测真实类型
 * 2. 只允许 PDF 和 DOCX
 * 3. 对 DOCX 执行 ZIP Bomb 防御
 * 4. 使用 Tika 提取正文
 */
@Component
public class TikaResumeDocumentExtractor {
    private static final String PDF_MEDIA_TYPE = "application/pdf";
    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String ZIP_MEDIA_TYPE = "application/zip";

    /**
     * 解析简历文件时，只解析主文档内容，不要继续解析文件里面嵌套的附件、图片、其他文档
     * 如果不限制嵌入文件，攻击者可能利用嵌套文档制造资源消耗攻击。
     */
    private static final EmbeddedDocumentExtractor NO_EMBEDDED_DOCUMENTS = new EmbeddedDocumentExtractor() {
        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return false;
        }
        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml) {
            // 不解析嵌入文档。
        }
    };
    // Tika 最大文本输出字符数
    private final int maxExtractedCharacters;
    // DOCX ZIP 最大 Entry数
    private final int maxArchiveEntries;
    // 单个 Entry 最大解压后大小
    private final long maxSingleEntryBytes;
    // 整个 DOCX 最大解压后大小
    private final long maxTotalUncompressedBytes;
    // 最大压缩比
    private final long maxCompressionRatio;
    // 达到该解压大小后，才开始检查压缩比，避免小文件产生误判。
    private final long compressionRatioCheckThreshold;

    public TikaResumeDocumentExtractor(
            @Value("${app.resume.security.max-extracted-characters:100000}")
            int maxExtractedCharacters,
            @Value("${app.resume.security.zip.max-entries:2000}")
            int maxArchiveEntries,
            @Value("${app.resume.security.zip.max-single-entry-bytes:20971520}")
            long maxSingleEntryBytes,
            @Value("${app.resume.security.zip.max-total-uncompressed-bytes:52428800}")
            long maxTotalUncompressedBytes,
            @Value("${app.resume.security.zip.max-compression-ratio:100}")
            long maxCompressionRatio,
            @Value("${app.resume.security.zip.compression-ratio-check-threshold:1048576}")
            long compressionRatioCheckThreshold
    ) {
        this.maxExtractedCharacters = maxExtractedCharacters;
        this.maxArchiveEntries = maxArchiveEntries;
        this.maxSingleEntryBytes = maxSingleEntryBytes;
        this.maxTotalUncompressedBytes = maxTotalUncompressedBytes;
        this.maxCompressionRatio = maxCompressionRatio;
        this.compressionRatioCheckThreshold = compressionRatioCheckThreshold;
    }

    // 检测、校验并提取简历内容
    public ResumeDocumentContent extract(MultipartFile file) {
        validateBasicFile(file); //基础文件校验
        String detectedMediaType = detectMediaType(file); //使用 Tika 检测文件真实类型
        AllowedDocumentType documentType = resolveAllowedDocumentType(file, detectedMediaType); //返回认可的文档类型以及文件扩展名
        //验证用户上传文件名里的扩展名 和 服务器根据文件内容检测出来的正确扩展名要一致
        validateFilenameExtension(file.getOriginalFilename(), documentType.extension());
        String extractedText = extractText(file);//提取到的正文内容
        if (extractedText.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "No extractable text was found in the resume");
        }
        return new ResumeDocumentContent(documentType.mediaType(), documentType.extension(), extractedText);
    }

    // 使用 Tika 检测文件真实类型
    private String detectMediaType(MultipartFile file) {
        DefaultDetector detector = new DefaultDetector(); // 创建 Tika 文件类型检测器
        Metadata metadata = new Metadata(); //Metadata 是 Tika 的元数据容器
        try (InputStream rawInputStream = file.getInputStream(); //从上传文件获取流
            BufferedInputStream bufferedInputStream = new BufferedInputStream(rawInputStream);// 缓冲
             // Tika 自己的 InputStream 包装
            TikaInputStream tikaInputStream = TikaInputStream.get(bufferedInputStream)) {
            // 真正检测文件类型
            MediaType mediaType = detector.detect(tikaInputStream, metadata);
            return mediaType.toString();// 对象转换成字符串
        } catch (IOException exception) {
          throw new BusinessException(ErrorCode.PARAMS_ERROR, "Unable to detect resume file type");
        }
    }

    /**
     * 只允许 PDF 和 DOCX。
     * 某些环境下 DOCX 可能先被识别为 ZIP，因此还会检查 DOCX 必须存在的内部结构。
     * 返回认可的文档类型以及文件扩展名
     */
    private AllowedDocumentType resolveAllowedDocumentType(MultipartFile file, String detectedMediaType) {
        if (PDF_MEDIA_TYPE.equals(detectedMediaType)) {
            return new AllowedDocumentType(PDF_MEDIA_TYPE, ".pdf");
        }
        if (DOCX_MEDIA_TYPE.equals(detectedMediaType) || ZIP_MEDIA_TYPE.equals(detectedMediaType)) {
            validateDocxArchive(file);
            return new AllowedDocumentType(DOCX_MEDIA_TYPE, ".docx");
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "Only PDF and DOCX resume files are supported");
    }

    /**
     * DOCX 本质上是 ZIP 容器。
     * 边解压边统计：
     * 1. Entry 数量
     * 2. 单个 Entry 解压大小
     * 3. 总解压大小
     * 4. 总体压缩比
     * 任意指标超过阈值都会立即终止。
     */
    private void validateDocxArchive(MultipartFile file) {
        // 原始压缩文件大小,这里防止文件大小为0，因为后面要计算压缩比： 解压大小 / 压缩大小
        long compressedFileSize = Math.max(file.getSize(), 1L);
        long totalUncompressedBytes = 0L; // 整个 DOCX 解压之后总大小
        int entryCount = 0; // ZIP里面每一个文件叫Entry，这里是Entry的数量
        boolean hasContentTypes = false;
        boolean hasWordDocument = false;
        byte[] buffer = new byte[8192]; //创建缓冲区
        try (InputStream rawInputStream = file.getInputStream(); //获取上传文件流
            BufferedInputStream bufferedInputStream = new BufferedInputStream(rawInputStream); //增加缓冲，提高读取效率
            ZipInputStream zipInputStream = new ZipInputStream(bufferedInputStream)) { //可以遍历内部文件的流
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) { //遍历 ZIP 内部文件
                entryCount++;
                if (entryCount > maxArchiveEntries) {//超出最大entry数
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "DOCX archive contains too many entries");
                }
                String entryName = entry.getName(); // 获取当前文件名
                if ("[Content_Types].xml".equals(entryName)) {
                    hasContentTypes = true;
                }
                if ("word/document.xml".equals(entryName)) {
                    hasWordDocument = true;
                }
                if (entry.isDirectory()) {// 跳过目录
                    zipInputStream.closeEntry();
                    continue;
                }
                long currentEntryBytes = 0L;
                int readLength;

                // 这里是真正的边解压边计数。不会先把 Entry 全部解压到 byte[] 或磁盘
                while ((readLength = zipInputStream.read(buffer)) != -1) { //从当前 ZIP Entry 中读取数据
                    if (readLength == 0) { //这次没有读取任何数据，跳过
                        continue;
                    }
                    currentEntryBytes += readLength; //统计当前 Entry 大小
                    totalUncompressedBytes += readLength; //统计整个 DOCX 大小
                    //防止单个文件太大
                    if (currentEntryBytes > maxSingleEntryBytes) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "DOCX entry exceeds the allowed uncompressed size");
                    }
                    //防止整个 DOCX 解压后太大
                    if (totalUncompressedBytes > maxTotalUncompressedBytes) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "DOCX archive exceeds the allowed uncompressed size");
                    }
                    //超过1MB再开始检查压缩比
                    if (totalUncompressedBytes > compressionRatioCheckThreshold
                            && totalUncompressedBytes > compressedFileSize * maxCompressionRatio) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR, "Suspicious DOCX compression ratio detected");
                    }
                }
                zipInputStream.closeEntry();//关闭当前 ZIP Entry
            }
        } catch (BusinessException exception) { //如果已经是我的业务异常，不要转换，直接继续往外抛
            throw exception;
        } catch (IOException exception) {//文件读取过程中的底层异常
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid or corrupted DOCX file"
            );
        }
        //普通 ZIP 即使扩展名改成 docx，只要缺少 DOCX 核心文件，就会被拒绝
        if (!hasContentTypes || !hasWordDocument) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "The uploaded file is not a valid DOCX document"
            );
        }
    }

    /**
     * 使用 Tika 提取 PDF 或 DOCX 正文。
     */
    private String extractText(MultipartFile file) {
        AutoDetectParser parser = new AutoDetectParser(); //创建一个自动识别文件类型的解析器
        //Tika解析文件后，需要一个地方保存提取出来的文字
        BodyContentHandler handler = new BodyContentHandler(maxExtractedCharacters);
        Metadata metadata = new Metadata(); //文件附加信息
        if (file.getOriginalFilename() != null) { //设置文件名
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getOriginalFilename());
        }
        //Parser 在解析过程中使用的上下文环境，可以向 Parser 提供额外的配置和组件
        ParseContext parseContext = new ParseContext();
        //关闭嵌入文档解析
        parseContext.set(EmbeddedDocumentExtractor.class, NO_EMBEDDED_DOCUMENTS);
        try (InputStream rawInputStream = file.getInputStream(); //读取上传文件
             BufferedInputStream bufferedInputStream = new BufferedInputStream(rawInputStream);//增加缓冲，提高读取效率
             TikaInputStream tikaInputStream = TikaInputStream.get(bufferedInputStream)) { //Tika专用流
            parser.parse(tikaInputStream, handler, metadata, parseContext);
            return handler.toString() //获取 Tika 提取出的文本
                    .replace("\u0000", "") //删除非法空字符
                    .strip(); //去除首尾空白字符
        } catch (SAXException exception) { //文件内部结构解析失败
            if (WriteLimitReachedException.isWriteLimitReached(exception)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Extracted resume text exceeds the allowed size"
                );
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Unable to extract text from resume");
        } catch (TikaException | IOException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Unable to parse resume document");
        }
    }

    // 基础文件校验
    private void validateBasicFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"Resume file cannot be empty");
        }
    }

    /**
     * 文件扩展名必须与检测出的真实类型一致。
     */
    private void validateFilenameExtension(String originalFilename, String expectedExtension) {
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(expectedExtension)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume filename extension does not match its actual type");
        }
    }

    /**
     * 后端认可的文档类型。
     */
    private record AllowedDocumentType(
            String mediaType,
            String extension
    ) {
    }
}