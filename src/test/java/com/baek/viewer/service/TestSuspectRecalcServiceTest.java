package com.baek.viewer.service;

import com.baek.viewer.repository.ApiRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TestSuspectRecalcServiceTest {

    @Mock
    private ApiRecordRepository recordRepository;
    @Mock
    private TestSuspectMatcher testSuspectMatcher;

    private TestSuspectRecalcService service;

    @BeforeEach
    void setUp() {
        service = new TestSuspectRecalcService(recordRepository, testSuspectMatcher);
    }

    @Test
    @DisplayName("레포 지정 시 findByRepositoryName 사용")
    void recalculate_byRepo() {
        when(testSuspectMatcher.currentKeywords()).thenReturn(List.of("test"));
        when(recordRepository.findByRepositoryName(eq("repo-x"), any(Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.recalculate(List.of("repo-x"));

        verify(recordRepository).findByRepositoryName(eq("repo-x"), any(Pageable.class));
        verify(recordRepository, never()).findAll(any(Pageable.class));
    }
}
