-- RAG 检索层：简历按结构切块后的向量存储（pgvector）。
-- 块是从 resume_version.content_json 派生出来的数据，随时可以重建。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE resume_chunk
(
    id BIGSERIAL PRIMARY KEY,

    resume_version_id BIGINT NOT NULL,

    -- 冗余的归属用户，便于按用户统计和排查。
    user_id BIGINT NOT NULL,

    section VARCHAR(16) NOT NULL,

    -- 块在版本内的确定性标识，例如 experience:0、project:1、skills。
    chunk_key VARCHAR(64) NOT NULL,

    ordinal INTEGER NOT NULL,

    content TEXT NOT NULL,

    -- 块内容的 SHA-256：内容不变的块在新版本里直接复用已有向量，不重复调用 embedding。
    content_hash CHAR(64) NOT NULL,

    -- 不同模型的向量不在同一个语义空间，检索必须按模型过滤；换模型后按需重新 embedding。
    embedding_model VARCHAR(64) NOT NULL,

    embedding vector(1024) NOT NULL,

    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 派生数据：版本被物理删除时一起删除（版本本身受 application / match_record 的 RESTRICT 保护）。
    CONSTRAINT fk_resume_chunk_version
        FOREIGN KEY (resume_version_id) REFERENCES resume_version(id) ON DELETE CASCADE,

    CONSTRAINT fk_resume_chunk_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,

    CONSTRAINT ck_resume_chunk_section
        CHECK (section IN ('SUMMARY', 'SKILLS', 'EXPERIENCE', 'PROJECT', 'EDUCATION', 'CERTIFICATE', 'TEXT')),

    CONSTRAINT ck_resume_chunk_ordinal
        CHECK (ordinal >= 0)
);

-- 幂等：并发索引同一个版本时 ON CONFLICT DO NOTHING，每个块只有一份。
CREATE UNIQUE INDEX uk_resume_chunk_version_model_key
    ON resume_chunk (resume_version_id, embedding_model, chunk_key);

-- embedding 缓存：按内容哈希和模型查找已有向量。
CREATE INDEX idx_resume_chunk_hash_model
    ON resume_chunk (content_hash, embedding_model);

-- 检索永远限定在一个简历版本内（几到几十个块）：唯一索引的前导列 (resume_version_id, embedding_model)
-- 定位到这些块后精确计算余弦距离，100% 召回且足够快。
-- 因此这里不建 HNSW / IVFFlat 近似索引：它们用召回换速度，只有跨大量向量检索时才值得，
-- 而且与 WHERE resume_version_id = ? 这种高选择性过滤叠加时，近似索引先取近邻再过滤，容易漏掉结果。

COMMENT ON TABLE resume_chunk IS 'Resume version chunks with embeddings for retrieval (derived data)';
COMMENT ON COLUMN resume_chunk.embedding_model IS 'Embedding model; vectors from different models must never be compared';
