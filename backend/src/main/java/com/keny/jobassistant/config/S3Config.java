package com.keny.jobassistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Amazon S3 客户端配置。
 * 凭证通过 AWS 默认凭证链读取：
 * 1. 环境变量
 * 2. JVM 系统属性
 * 3. 本地 AWS 配置文件
 * 4. EC2/ECS IAM Role
 * 不在代码中硬编码 AccessKey。
 */
@Configuration
public class S3Config {

    @Bean(destroyMethod = "close")
    public S3Client s3Client(@Value("${app.resume.s3.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Bean(destroyMethod = "close")
    public S3Presigner s3Presigner(@Value("${app.resume.s3.region}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}