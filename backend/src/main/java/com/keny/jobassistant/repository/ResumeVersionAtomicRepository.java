package com.keny.jobassistant.repository;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 在用户上传新简历版本时，用一条 SQL 完成：
 * 当前简历版本号 +1
 * 更新 resume 当前最新数据
 * 插入 resume_version 历史版本记录
 * 返回新版本号
 */
@Repository
public class ResumeVersionAtomicRepository {

    /**
     * 一条 SQL 同时完成版本号递增、当前数据更新和历史快照插入。
     */
    private static final String CREATE_NEXT_VERSION_SQL = """
            WITH updated_resume AS
            (
                UPDATE resume
                SET latest_version_number = latest_version_number + 1,
                    resume_name = :resumeName,
                    file_url = :fileUrl,
                    parsed_json = CAST(:contentJson AS JSONB),
                    update_time = CURRENT_TIMESTAMP
                WHERE id = :resumeId
                  AND user_id = :userId
                RETURNING
                    id,
                    latest_version_number,
                    parsed_json
            )
            INSERT INTO resume_version
            (
                resume_id,
                version_number,
                content_json,
                create_time
            )
            SELECT
                id,
                latest_version_number,
                parsed_json,
                CURRENT_TIMESTAMP
            FROM updated_resume
            RETURNING version_number
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 为指定简历创建下一个版本。
     * 调用该方法时，外层必须已经开启数据库事务。
     * @param resumeId 简历 ID
     * @param userId 当前用户 ID
     * @param resumeName 新版本简历名称
     * @param fileUrl 当前文件下载地址
     * @param contentJson AI 解析结果
     * @return 创建成功时返回版本号；简历不存在或不属于当前用户时返回空
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Integer> createNextVersion(
            Long resumeId,
            Long userId,
            String resumeName,
            String fileUrl,
            JsonNode contentJson
    ) {
        // 确保当前事务中尚未写入数据库的 JPA 数据先执行。
        entityManager.flush();

        Query query = entityManager.createNativeQuery(CREATE_NEXT_VERSION_SQL);
        query.setParameter("resumeId", resumeId);
        query.setParameter("userId", userId);
        query.setParameter("resumeName", resumeName);
        query.setParameter("fileUrl", fileUrl);
        query.setParameter("contentJson", contentJson == null ? null : contentJson.toString());

        @SuppressWarnings("unchecked")
        List<Object> result = query.getResultList();
        if (result.isEmpty()) {
            return Optional.empty();
        }
        Number versionNumber = (Number) result.get(0);
        return Optional.of(versionNumber.intValue());
    }
}