package com.baek.viewer;

import com.baek.viewer.model.ApiRecord;
import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.service.ApiStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * DDL 신설 컬럼 {@code auto_analyzed_status} 가 null 인 기존 행에 순수 자동 판정·불일치 플래그를 채운다.
 */
@Component
@Order(100)
public class AutoAnalyzedStatusBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoAnalyzedStatusBackfillRunner.class);

    private final ApiRecordRepository apiRecordRepository;
    private final ApiStorageService apiStorageService;

    public AutoAnalyzedStatusBackfillRunner(ApiRecordRepository apiRecordRepository,
                                            ApiStorageService apiStorageService) {
        this.apiRecordRepository = apiRecordRepository;
        this.apiStorageService = apiStorageService;
    }

    @Override
    public void run(ApplicationArguments args) {
        final int pageSize = 500;
        long total = 0;
        int pageIdx = 0;
        while (true) {
            Page<ApiRecord> page = apiRecordRepository.findByAutoAnalyzedStatusIsNull(
                    PageRequest.of(pageIdx, pageSize, Sort.by(Sort.Direction.ASC, "id")));
            if (page.isEmpty()) {
                break;
            }
            for (ApiRecord r : page.getContent()) {
                apiStorageService.refreshAutoAnalyzedStatusAndMismatchFlag(r);
            }
            apiRecordRepository.saveAll(page.getContent());
            total += page.getNumberOfElements();
            if (!page.hasNext()) {
                break;
            }
            pageIdx++;
        }
        if (total > 0) {
            log.info("[auto_analyzed_status 백필] {}건 갱신", total);
        }
    }
}
