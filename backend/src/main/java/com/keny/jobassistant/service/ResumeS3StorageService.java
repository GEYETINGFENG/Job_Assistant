package com.keny.jobassistant.service;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Amazon S3 简历文件操作服务。
 */
@Slf4j
@Service
public class ResumeS3StorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;

    public ResumeS3StorageService(S3Client s3Client,
                                  S3Presigner s3Presigner,
                                  @Value("${app.resume.s3.bucket}") String bucketName) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucketName = bucketName;
    }

    /**
     * 为指定临时对象 Key 生成 PUT 预签名 URL。
     * 该方法不会真正上传文件到 S3，而是生成一个具有临时权限的 URL，允许客户端在有效期内直接向 S3 上传文件。
     *  @param objectKey      S3 中保存对象的路径，例如 resume-uploads/1/xxx.pdf
     *  @param contentType    文件类型，例如 application/pdf
     *  @param duration       预签名 URL 有效时间
     *  @return 包含上传 URL、过期时间以及必要 Header 的结果
     */
    public PresignedUploadResult createPresignedUpload(String objectKey, String contentType, Duration duration) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName) //指定目标 Bucket
                .key(objectKey) //指定文件最终在 S3 中保存的位置
                .contentType(contentType)
                .build();
        // 创建预签名请求
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(duration) //设置 URL 有效时间
                .putObjectRequest(putObjectRequest) //绑定刚刚创建的 PUT 请求
                .build();

        try {// 调用 AWS SDK 生成预签名 PUT URL
            PresignedPutObjectRequest result = s3Presigner.presignPutObject(presignRequest);
            return new PresignedUploadResult(result.url().toString(), Instant.now().plus(duration), Map.of("Content-Type", contentType));
        } catch (RuntimeException exception) {
            log.error("Failed to create S3 presigned upload URL", exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to create upload URL");
        }
    }

    /**
     * 只读取 S3 对象元数据，不下载文件正文。
     * 确认：
     * 文件是否真的存在
     * 文件大小是否正确
     * 文件类型是否符合预期
     * 从而决定是否继续Tika + AI 解析
     */
    public StoredObjectMetadata getObjectMetadata(String objectKey) {
        HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucketName).key(objectKey).build();
        //HeadObject 请求只会返回对象的信息，例如：文件大小,文件类型以及Etag
        //这里使用 HeadObject 是为了在解析文件之前，先快速确认用户上传的文件是否已经成功到达 S3

        try {
            HeadObjectResponse response = s3Client.headObject(request);
            //如果对象存在,返回200,并包含文件信息
            return new StoredObjectMetadata(response.contentLength(), response.contentType(), response.eTag());
        } catch (S3Exception exception) {
            //404说明用户调用complete时，对应文件并没有真正上传成功。
            if (exception.statusCode() == 404) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Uploaded S3 object does not exist");
            }
            // 其他S3异常
            log.error("Failed to read S3 object metadata, statusCode={}", exception.statusCode(), exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to verify uploaded file");
        }
    }

    /**
     * 使用流式读取将 S3 对象保存到临时文件。
     */
    public void downloadObject(String objectKey, Path targetPath) {
        //GetObject 获取 S3 中真实的文件数据。
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucketName).key(objectKey).build();
        //调用S3下载文件
        //ResponseInputStream包含：1. S3返回的文件数据流 2. HTTP响应相关信息
        try (ResponseInputStream<GetObjectResponse> inputStream = s3Client.getObject(request);
             //创建本地文件输出流
             //如果目标文件已经存在，清空原内容重新写入。
             OutputStream outputStream = Files.newOutputStream(targetPath, StandardOpenOption.TRUNCATE_EXISTING)) {
             inputStream.transferTo(outputStream); //将S3输入流中的数据复制到本地文件
        } catch (S3Exception | IOException exception) {
            log.error("Failed to download S3 object, objectKey={}", objectKey, exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to download uploaded file");
        }
    }

    /**
     * 将已经通过 Tika 校验的临时文件上传到正式对象 Key。
     * 这里上传的是后端刚刚解析过的那份临时文件，
     * 而不是直接复制客户端仍可覆盖的 staging 对象。
     * @param objectKey   正式保存的S3对象Key，例如 resumes/2/resume-13.pdf
     * @param filePath    后端校验后的本地临时文件路径
     * @param contentType 文件真实类型，例如 application/pdf
     */
    public void uploadValidatedObject(String objectKey, Path filePath, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder() //创建正式文件上传请求。
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .build();

        try { //上传本地临时文件到S3正式路径:AWS SDK会读取这个本地文件，通过流的方式上传到S3。
            s3Client.putObject(request, RequestBody.fromFile(filePath));
        } catch (S3Exception exception) {
            log.error("Failed to store validated S3 object, objectKey={}", objectKey, exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to store validated resume file");
        }
    }

    /**
     * 生成短期下载 URL
     * @param objectKey S3对象Key，例如 resumes/2/resume-13.pdf
     * @param duration 下载URL有效时间
     * @return 临时下载地址
     */
    public String createPresignedDownloadUrl(String objectKey, Duration duration) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucketName).key(objectKey).build();
        //AWS SDK会根据：Bucket,Object Key,当前时间,过期时间来计算签名
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(getObjectRequest)
                .build();
        try {
            //生成预签名下载URL
            PresignedGetObjectRequest result = s3Presigner.presignGetObject(presignRequest);
            return result.url().toString();
        } catch (RuntimeException exception) {
            log.error("Failed to create S3 presigned download URL", exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to create download URL");
        }
    }

    /**
     * 清理对象。
     * 用于删除S3中的临时文件或无效文件。
     * Eg.用户上传文件后没有完成解析/ Tika检测失败 /AI解析失败
     * 都需要清理 resume-uploads/
     * 清理失败不覆盖原始业务异常，只记录日志。
     */
    public void deleteObjectQuietly(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build();
        try {
            s3Client.deleteObject(request);
        } catch (RuntimeException exception) {
            //删除失败只记录日志,不抛出异常
            log.warn("Failed to delete S3 object, objectKey={}", objectKey, exception);
        }
    }

    public record PresignedUploadResult(
            String uploadUrl,
            Instant expiresAt,
            Map<String, String> requiredHeaders
    ) {
    }

    public record StoredObjectMetadata(
            long contentLength, //文件大小(byte) 用于校验上传文件大小是否和预上传阶段一致
            String contentType, //S3记录的文件类型,后续仍需要Tika根据文件内容检测真实类型
            String eTag //文件唯一标识,可以用于文件一致性校验和去重
    ) {
    }
}