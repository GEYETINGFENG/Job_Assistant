package com.keny.jobassistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeChunkerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ResumeChunker chunker = new ResumeChunker();

    @Test
    void structuredResumeShouldBeChunkedBySection() throws Exception {
        var content = MAPPER.readTree("""
                {"summary": "Backend engineer.", "skills": ["Java", "Kafka"], "languages": ["English"],
                 "experience": [{"company": "Acme", "position": "Engineer", "startDate": "2021", "endDate": "2023",
                                 "description": "Built payment APIs."},
                                {"company": "", "position": "", "description": ""}],
                 "projects": [{"name": "Search", "role": "Lead", "technologies": ["Elasticsearch"], "description": "Indexed docs."}],
                 "education": [{"school": "MIT", "degree": "BS", "major": "CS", "startDate": "", "endDate": "2020"}],
                 "certificates": [{"name": "AWS SAA", "issuer": "Amazon", "date": "2022"}],
                 "rawText": "should not be used"}
                """);

        List<ResumeChunk> chunks = chunker.chunk(content);

        assertThat(chunks).extracting(ResumeChunk::key)
                .containsExactly("summary", "skills", "experience:0", "project:0", "education:0", "certificates");
        assertThat(chunks).extracting(ResumeChunk::ordinal).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(chunks.get(1).content()).isEqualTo("Skills: Java, Kafka\nLanguages: English");
        assertThat(chunks.get(2).content()).isEqualTo("Experience: Engineer at Acme (2021 - 2023)\nBuilt payment APIs.");
        assertThat(chunks.get(3).content()).isEqualTo("Project: Search (Lead)\nTechnologies: Elasticsearch\nIndexed docs.");
        assertThat(chunks.get(4).content()).isEqualTo("Education: BS in CS, MIT (2020)");
        assertThat(chunks.get(5).content()).isEqualTo("Certificates: AWS SAA (Amazon, 2022)");
    }

    /** 空的经历条目被跳过，但后续条目的 key 仍按原始下标，和简历结构一一对应。 */
    @Test
    void emptyEntriesShouldBeSkippedWithoutShiftingKeys() throws Exception {
        var content = MAPPER.readTree("""
                {"experience": [{"company": ""}, {"company": "Beta", "position": "Dev"}]}
                """);

        assertThat(chunker.chunk(content)).extracting(ResumeChunk::key).containsExactly("experience:1");
    }

    @Test
    void resumeWithOnlyRawTextShouldFallBackToFixedSize() throws Exception {
        var content = MAPPER.readTree("{\"rawText\": \"%s\"}".formatted("word ".repeat(400)));

        List<ResumeChunk> chunks = chunker.chunk(content);

        assertThat(chunks).hasSizeGreaterThan(1).allMatch(chunk -> chunk.section() == ChunkSection.TEXT);
        assertThat(chunks.get(0).key()).isEqualTo("text:0");
    }

    @Test
    void fixedSizeChunksShouldOverlapAndBreakOnWhitespace() {
        String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa";

        List<ResumeChunk> chunks = ResumeChunker.fixedSize(text, 20, 8);

        assertThat(chunks).allMatch(chunk -> !chunk.content().startsWith(" ") && chunk.content().length() <= 20);
        // 相邻块有重叠：后一块的开头出现在前一块里。
        for (int i = 1; i < chunks.size(); i++) {
            String firstWord = chunks.get(i).content().split(" ")[0];
            assertThat(chunks.get(i - 1).content()).contains(firstWord);
        }
        assertThat(String.join(" ", chunks.stream().map(ResumeChunk::content).toList())).contains("kappa");
    }

    @Test
    void invalidFixedSizeParametersShouldBeRejected() {
        assertThatThrownBy(() -> ResumeChunker.fixedSize("text", 10, 10)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyContentShouldProduceNoChunks() throws Exception {
        assertThat(chunker.chunk(MAPPER.readTree("{}"))).isEmpty();
    }
}
