package com.baek.viewer.service;

import com.baek.viewer.ai.InternalOpenAiCompatibleClient;
import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import com.baek.viewer.repository.ScheduleConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiSummaryServiceTest {

    @Mock private ApiRecordRepository apiRecordRepository;
    @Mock private RepoConfigRepository repoConfigRepository;
    @Mock private ScheduleConfigRepository scheduleConfigRepository;
    @Mock private DbMonitorService dbMonitorService;
    @Mock private GlobalConfigRepository globalConfigRepository;
    @Mock private InternalOpenAiCompatibleClient aiClient;

    @InjectMocks private AiSummaryService service;

    @Test
    void getCached_configuredTrueWhenDashboardEnabledWithoutBaseUrl() {
        GlobalConfig gc = new GlobalConfig();
        gc.setAiDashboardEnabled("Y");
        gc.setAiOpenApiBaseUrl(null);
        gc.setAiDashboardSummary("batch summary");
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(gc));

        Map<String, Object> m = service.getCached();
        assertThat(m.get("configured")).isEqualTo(true);
        assertThat(m.get("dashboardEnabled")).isEqualTo(true);
        assertThat(m.get("summary")).isEqualTo("batch summary");
    }

    @Test
    void getCached_configuredFalseWhenDashboardDisabledEvenWithSummary() {
        GlobalConfig gc = new GlobalConfig();
        gc.setAiDashboardEnabled("N");
        gc.setAiDashboardSummary("hidden");
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.of(gc));

        Map<String, Object> m = service.getCached();
        assertThat(m.get("configured")).isEqualTo(false);
        assertThat(m.get("dashboardEnabled")).isEqualTo(false);
        assertThat(m.get("summary")).isEqualTo("hidden");
    }

    @Test
    void getCached_noGlobalRow() {
        when(globalConfigRepository.findById(1L)).thenReturn(Optional.empty());
        Map<String, Object> m = service.getCached();
        assertThat(m.get("configured")).isEqualTo(false);
        assertThat(m.get("dashboardEnabled")).isEqualTo(false);
    }
}
