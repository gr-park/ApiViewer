package com.baek.viewer.service;

import com.baek.viewer.ai.InternalOpenAiCompatibleClient;
import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelatedMenuDeficiencyRecalcServiceTest {

    @Mock
    private ApiRecordRepository recordRepository;
    @Mock
    private RelatedMenuDeficiencyChecker deficiencyChecker;
    @Mock
    private RelatedMenuAiAutoFillService relatedMenuAiAutoFillService;
    @Mock
    private GlobalConfigRepository globalConfigRepository;
    @Mock
    private RepoConfigRepository repoConfigRepository;
    @Mock
    private InternalOpenAiCompatibleClient internalOpenAiCompatibleClient;

    private RelatedMenuDeficiencyRecalcService service;

    @BeforeEach
    void setUp() {
        service = new RelatedMenuDeficiencyRecalcService(
                recordRepository,
                deficiencyChecker,
                relatedMenuAiAutoFillService,
                globalConfigRepository,
                repoConfigRepository,
                internalOpenAiCompatibleClient);
    }

    @Test
    @DisplayName("false→true 변경 시 저장 및 nowDeficient 카운트")
    void recalculate_turnsDeficient() {
        ApiRecord r = record(false);
        when(recordRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r)))
                .thenReturn(Page.empty());
        when(deficiencyChecker.isDeficient(r)).thenReturn(true);

        RelatedMenuDeficiencyRecalcService.RecalcResult result = service.recalculate(List.of(), false);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.nowDeficient()).isEqualTo(1);
        assertThat(result.nowOk()).isZero();
        ArgumentCaptor<List<ApiRecord>> cap = ArgumentCaptor.forClass(List.class);
        verify(recordRepository).saveAll(cap.capture());
        assertThat(cap.getValue().get(0).getRelatedMenuDeficient()).isTrue();
    }

    @Test
    @DisplayName("true→false 변경 시 nowOk 카운트")
    void recalculate_turnsOk() {
        ApiRecord r = record(true);
        when(recordRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r)))
                .thenReturn(Page.empty());
        when(deficiencyChecker.isDeficient(r)).thenReturn(false);

        RelatedMenuDeficiencyRecalcService.RecalcResult result = service.recalculate(List.of(), false);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.nowOk()).isEqualTo(1);
        assertThat(result.nowDeficient()).isZero();
    }

    @Test
    @DisplayName("변경 없으면 saveAll 호출 안 함")
    void recalculate_unchanged_skipsSave() {
        ApiRecord r = record(true);
        when(recordRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r)))
                .thenReturn(Page.empty());
        when(deficiencyChecker.isDeficient(r)).thenReturn(true);

        RelatedMenuDeficiencyRecalcService.RecalcResult result = service.recalculate(List.of(), false);

        assertThat(result.updated()).isZero();
        verify(recordRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("레포 지정 시 findByRepositoryName 사용")
    void recalculate_byRepo() {
        when(recordRepository.findByRepositoryName(eq("my-repo"), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.recalculate(List.of("my-repo"), false);

        verify(recordRepository).findByRepositoryName(eq("my-repo"), any(Pageable.class));
        verify(recordRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("applyAiAfter 시 레포별 AI 보완 및 잔여 미흡 건수")
    void recalculate_withAiFill() {
        when(recordRepository.findByRepositoryName(eq("my-repo"), any(Pageable.class)))
                .thenReturn(Page.empty());
        GlobalConfig gc = new GlobalConfig();
        gc.setAiApiEnabled("Y");
        gc.setAiOpenApiBaseUrl("http://ai");
        gc.setAiOpenApiToken("tok");
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(gc));
        when(relatedMenuAiAutoFillService.fillDeficientInRepository("my-repo")).thenReturn(3);
        when(recordRepository.countRelatedMenuDeficientForRepos(List.of("my-repo"))).thenReturn(0L);

        RelatedMenuDeficiencyRecalcService.RecalcResult result =
                service.recalculate(List.of("my-repo"), true);

        assertThat(result.aiApplied()).isEqualTo(3);
        assertThat(result.remainingDeficient()).isZero();
        verify(internalOpenAiCompatibleClient).chatCompletion(eq(gc), eq("ping"), eq(8));
    }

    private static ApiRecord record(boolean deficient) {
        ApiRecord r = new ApiRecord();
        r.setRelatedMenuDeficient(deficient);
        return r;
    }
}
