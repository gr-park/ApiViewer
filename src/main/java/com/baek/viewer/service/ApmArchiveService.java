package com.baek.viewer.service;

import com.baek.viewer.model.ApmCallData;
import com.baek.viewer.repository.ApmCallDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * APM 호출이력 아카이브 서비스.
 * 지정 기간(기본 1년) 이상 지난 데이터를 CSV로 백업 후 원본 테이블에서 삭제.
 */
@Service
public class ApmArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ApmArchiveService.class);
    private static final Path ARCHIVE_DIR = Paths.get("./data/archive");

    private final ApmCallDataRepository apmRepo;

    public ApmArchiveService(ApmCallDataRepository apmRepo) {
        this.apmRepo = apmRepo;
    }

    /**
     * keepDays 일 이전 데이터를 CSV로 백업 후 삭제.
     * @param keepDays 유지할 최근 일수 (기본 365)
     * @param dryRun true면 삭제 없이 건수만 반환
     */
    @Transactional
    public Map<String, Object> archive(int keepDays, boolean dryRun) throws Exception {
        LocalDate cutoff = LocalDate.now().minusDays(Math.max(1, keepDays));
        log.info("[APM 아카이브] 시작: cutoff={} (keepDays={}일), dryRun={}", cutoff, keepDays, dryRun);

        long totalCount = apmRepo.countByCallDateBefore(cutoff);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetCount", totalCount);
        result.put("cutoff", cutoff.toString());
        result.put("dryRun", dryRun);

        if (totalCount == 0) {
            log.info("[APM 아카이브] 백업할 데이터 없음");
            result.put("archived", 0);
            return result;
        }
        if (dryRun) {
            log.info("[APM 아카이브] (dry-run) 대상 {}건", totalCount);
            result.put("archived", 0);
            return result;
        }

        // CSV 저장 (1만건씩 배치 조회 권장하지만 일단 단순화)
        Files.createDirectories(ARCHIVE_DIR);
        String ts = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        Path csvFile = ARCHIVE_DIR.resolve("apm_call_data_before_" + cutoff + "_archived_" + ts + ".csv");

        List<ApmCallData> oldData = apmRepo.findByCallDateBefore(cutoff);
        try (BufferedWriter w = Files.newBufferedWriter(csvFile)) {
            w.write("id,repository_name,api_path,call_date,source,call_count,error_count,error_message,class_name\n");
            for (ApmCallData d : oldData) {
                w.write(csvLine(d));
                w.newLine();
            }
        }
        log.info("[APM 아카이브] CSV 저장 완료: {} ({}건)", csvFile, oldData.size());

        // 일괄 삭제 (native DELETE)
        int deleted = apmRepo.deleteByCallDateBefore(cutoff);
        log.info("[APM 아카이브] DB에서 {}건 삭제 완료", deleted);

        result.put("archived", deleted);
        result.put("csvPath", csvFile.toAbsolutePath().toString());
        return result;
    }

    private String csvLine(ApmCallData d) {
        return String.join(",",
                String.valueOf(d.getId()),
                esc(d.getRepositoryName()),
                esc(d.getApiPath()),
                d.getCallDate() != null ? d.getCallDate().toString() : "",
                esc(d.getSource()),
                String.valueOf(d.getCallCount()),
                String.valueOf(d.getErrorCount()),
                esc(d.getErrorMessage()),
                esc(d.getClassName()));
    }

    private String esc(String s) {
        if (s == null) return "";
        boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String escaped = s.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }

    /** 아카이브 디렉토리의 파일 목록 */
    public List<Map<String, Object>> listArchives() throws Exception {
        if (!Files.exists(ARCHIVE_DIR)) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        try (var stream = Files.list(ARCHIVE_DIR)) {
            stream.filter(p -> p.toString().endsWith(".csv"))
                  .sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try {
                          Map<String, Object> m = new LinkedHashMap<>();
                          m.put("name", p.getFileName().toString());
                          m.put("size", Files.size(p));
                          m.put("modified", Files.getLastModifiedTime(p).toString());
                          list.add(m);
                      } catch (Exception e) {}
                  });
        }
        return list;
    }
}
