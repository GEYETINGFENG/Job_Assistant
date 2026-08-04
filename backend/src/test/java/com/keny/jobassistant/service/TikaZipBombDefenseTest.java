package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DOCX ZIP Bomb 防御测试。
 */
class TikaZipBombDefenseTest {

    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    // 压缩文件本身很小，但解压内容超过总大小限制时，应在解压过程中立即终止并拒绝文件
    @Test
    void shouldRejectDocxWhenUncompressedSizeExceedsLimit() {
        //构造包含大量重复字符的 DOCX,重复字符压缩率很高，所以上传文件本身会很小，
        //但解压后的 word/document.xml 会超过 20 KB。
        MockMultipartFile zipBombFile = createHighlyCompressedDocx(20_000);

        /*
         * 测试专用阈值：
         * 最大 Entry 数：100
         * 单个 Entry 最大解压大小：100 KB
         * 总解压大小：4 KB
         * 压缩比检查暂时设置得很宽松，确保本次测试命中总解压大小分支。
         */
        TikaResumeDocumentExtractor extractor =
                new TikaResumeDocumentExtractor(
                        100_000,
                        100,
                        100_000,
                        4_096,
                        100_000,
                        1_000_000
                );

        //文件压缩后本身应该很小，说明不是上传文件大小校验将它拒绝
        assertThat(zipBombFile.getSize()).isLessThan(4_096);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> extractor.extract(zipBombFile)
        );

        assertThat(exception.getCode())
                .isEqualTo(ErrorCode.PARAMS_ERROR.getCode());

        assertThat(exception.getDescription())
                .isEqualTo("DOCX archive exceeds the allowed uncompressed size");
    }

    /**
     * 构造压缩后很小、解压后很大的 DOCX。
     */
    private MockMultipartFile createHighlyCompressedDocx(int repeatedCharacters) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream)) {

            zipOutputStream.setLevel(Deflater.BEST_COMPRESSION); //设置最高压缩率

            addEntry(
                    zipOutputStream,
                    "[Content_Types].xml",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                        <Default Extension="rels"
                                 ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                        <Default Extension="xml"
                                 ContentType="application/xml"/>
                        <Override PartName="/word/document.xml"
                                  ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """
            );

            addEntry(
                    zipOutputStream,
                    "_rels/.rels",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                        <Relationship Id="rId1"
                                      Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                                      Target="word/document.xml"/>
                    </Relationships>
                    """
            );

            addEntry(
                    zipOutputStream,
                    "word/document.xml",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                        <w:body>
                            <w:p>
                                <w:r>
                                    <w:t>%s</w:t>
                                </w:r>
                            </w:p>
                        </w:body>
                    </w:document>
                    """.formatted("A".repeat(repeatedCharacters))
            );

            zipOutputStream.finish();

            return new MockMultipartFile(
                    "file",
                    "zip-bomb.docx",
                    DOCX_MEDIA_TYPE,
                    outputStream.toByteArray()
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 向 DOCX ZIP 容器中添加一个 Entry。
     */
    private void addEntry(
            ZipOutputStream zipOutputStream,
            String entryName,
            String content
    ) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(entryName));
        zipOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
        zipOutputStream.closeEntry();
    }
}