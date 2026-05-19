package com.baek.viewer.service;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PathParamPatternRecalcServiceTest {

    @Mock
    private ApiRecordRepository recordRepository;

    private PathParamPatternRecalcService service;

    @BeforeEach
    void setUp() {
        service = new PathParamPatternRecalcService(recordRepository);
    }

    @Test
    @DisplayName("레포 지정 시 findByRepositoryName 으로 조회")
    void recalculate_byRepo() {
        when(recordRepository.findByRepositoryName(eq("repo-a"), any(Pageable.class)))
                .thenReturn(Page.empty());

        PathParamPatternRecalcService.RecalcResult result = service.recalculate(List.of("repo-a"));

        assertThat(result.repoNames()).containsExactly("repo-a");
        verify(recordRepository).findByRepositoryName(eq("repo-a"), any(Pageable.class));
        verify(recordRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("미지정 시 findAll")
    void recalculate_allRepos() {
        when(recordRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        service.recalculate(List.of());

        verify(recordRepository).findAll(any(Pageable.class));
    }
}
