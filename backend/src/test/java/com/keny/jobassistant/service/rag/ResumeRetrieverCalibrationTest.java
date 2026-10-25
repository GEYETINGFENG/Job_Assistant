package com.keny.jobassistant.service.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeRetrieverCalibrationTest {

    @Test
    void shouldUseCalibrationOfTheEmbeddingModel() {
        assertThat(ResumeRetriever.calibration(HashingEmbeddingClient.MODEL, null, null))
                .isEqualTo(new ResumeRetriever.Calibration(3, 0.05));
        assertThat(ResumeRetriever.calibration("text-embedding-v4", null, null))
                .isEqualTo(new ResumeRetriever.Calibration(2, 0.35));
    }

    @Test
    void explicitValuesShouldOverrideCalibration() {
        assertThat(ResumeRetriever.calibration("text-embedding-v4", 5, null))
                .isEqualTo(new ResumeRetriever.Calibration(5, 0.35));
        assertThat(ResumeRetriever.calibration("text-embedding-v4", null, 0.5))
                .isEqualTo(new ResumeRetriever.Calibration(2, 0.5));
    }

    /** 没校准过的模型不能悄悄沿用别的模型的参数；两个参数都显式配置了才可以用。 */
    @Test
    void uncalibratedModelShouldRequireBothValues() {
        assertThatThrownBy(() -> ResumeRetriever.calibration("text-embedding-v3", null, 0.4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAG_TOP_K");
        assertThat(ResumeRetriever.calibration("text-embedding-v3", 3, 0.4))
                .isEqualTo(new ResumeRetriever.Calibration(3, 0.4));
    }
}
