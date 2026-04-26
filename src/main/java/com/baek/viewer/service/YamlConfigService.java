package com.baek.viewer.service;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.JiraConfig;
import com.baek.viewer.model.JiraUserMapping;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.model.ReposYamlConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.JiraConfigRepository;
import com.baek.viewer.repository.JiraUserMappingRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class YamlConfigService {

    private static final Logger log = LoggerFactory.getLogger(YamlConfigService.class);

    private final RepoConfigRepository repoRepo;
    private final GlobalConfigRepository globalRepo;
    private final JiraConfigRepository jiraConfigRepo;
    private final JiraUserMappingRepository jiraUserMappingRepo;

    @Value("${api.viewer.repos-config-path:./repos-config.yml}")
    private String defaultConfigPath;

    public YamlConfigService(RepoConfigRepository repoRepo, GlobalConfigRepository globalRepo,
                             JiraConfigRepository jiraConfigRepo, JiraUserMappingRepository jiraUserMappingRepo) {
        this.repoRepo = repoRepo;
        this.globalRepo = globalRepo;
        this.jiraConfigRepo = jiraConfigRepo;
        this.jiraUserMappingRepo = jiraUserMappingRepo;
    }

    public String getDefaultConfigPath() {
        return defaultConfigPath;
    }

    /**
     * YAML 파일을 파싱하여 DB에 저장합니다.
     * 기존 레포는 업데이트, 신규 레포는 추가합니다.
     */
    @Transactional
    public Map<String, Object> importFromYaml(String filePath) throws Exception {
        log.info("[YAML 임포트] 파일 경로={}", filePath);
        Yaml yaml = new Yaml(new Constructor(ReposYamlConfig.class, new LoaderOptions()));

        ReposYamlConfig config;
        try (InputStream is = new FileInputStream(filePath)) {
            config = yaml.load(is);
        }

        if (config == null) throw new IllegalArgumentException("YAML 파일이 비어 있습니다.");
        return processYamlConfig(config);
    }

    /**
     * 활성 yml (외부 파일 우선, 없으면 classpath) 에서 global.password 만 읽어 반환.
     * DB password 가 비어있을 때 인증 폴백용.
     */
    public String readActivePasswordFromYaml() {
        Yaml yaml = new Yaml(new Constructor(ReposYamlConfig.class, new LoaderOptions()));
        ReposYamlConfig config = null;
        // 1. 외부 파일
        java.io.File external = new java.io.File(getDefaultConfigPath());
        if (external.exists()) {
            try (InputStream is = new FileInputStream(external)) {
                config = yaml.load(is);
            } catch (Exception e) {
                log.warn("[YAML password 조회] 외부 파일 파싱 실패: {}", e.getMessage());
            }
        }
        // 2. classpath 폴백
        if (config == null) {
            try (InputStream is = new org.springframework.core.io.ClassPathResource("repos-config.yml").getInputStream()) {
                config = yaml.load(is);
            } catch (Exception e) {
                log.warn("[YAML password 조회] classpath 파싱 실패: {}", e.getMessage());
                return null;
            }
        }
        if (config == null || config.getGlobal() == null) return null;
        return config.getGlobal().getPassword();
    }

    public Map<String, Object> importFromYamlContent(String content) throws Exception {
        log.info("[YAML 내용 임포트] 크기={}bytes", content.length());
        Yaml yaml = new Yaml(new Constructor(ReposYamlConfig.class, new LoaderOptions()));
        ReposYamlConfig config = yaml.load(content);
        if (config == null) throw new IllegalArgumentException("YAML 내용이 비어 있습니다.");
        return processYamlConfig(config);
    }

    /** Y/true/yes/N/false/no → "Y" 또는 "N" 정규화 (YAML Boolean 자동변환 대응) */
    private String normalizeYN(String v) {
        if (v == null) return "N";
        String s = v.trim().toUpperCase();
        return ("Y".equals(s) || "TRUE".equals(s) || "YES".equals(s) || "1".equals(s)) ? "Y" : "N";
    }

    private Map<String, Object> processYamlConfig(ReposYamlConfig config) {

        int added = 0, updated = 0;

        // 공통 설정 저장
        if (config.getGlobal() != null) {
            GlobalConfig gc = globalRepo.findById(1L).orElse(new GlobalConfig());
            ReposYamlConfig.GlobalSection g = config.getGlobal();
            if (g.getPeriod() != null) {
                if (g.getPeriod().getStartDate() != null) gc.setStartDate(g.getPeriod().getStartDate());
                if (g.getPeriod().getEndDate()   != null) gc.setEndDate(g.getPeriod().getEndDate());
            }
            if (g.getReviewThreshold() != null) gc.setReviewThreshold(g.getReviewThreshold());
            if (g.getPassword() != null) gc.setPassword(g.getPassword());
            if (g.getPageSize() != null) gc.setPageSize(g.getPageSize());
            if (g.getPageNavSize() != null) gc.setPageNavSize(g.getPageNavSize());

            // 메일 설정
            if (g.getSmtpHost() != null) gc.setSmtpHost(g.getSmtpHost());
            if (g.getSmtpPort() != null) gc.setSmtpPort(g.getSmtpPort());
            if (g.getSmtpUsername() != null) gc.setSmtpUsername(g.getSmtpUsername());
            if (g.getSmtpPassword() != null) gc.setSmtpPassword(g.getSmtpPassword());
            if (g.getMailFrom() != null) gc.setMailFrom(g.getMailFrom());
            if (g.getMailTo() != null) gc.setMailTo(g.getMailTo());
            if (g.getApmLogLevel() != null) {
                gc.setApmLogLevel(g.getApmLogLevel().trim().toUpperCase());
            }
            if (g.getLogTailLines() != null) gc.setLogTailLines(g.getLogTailLines());
            if (g.getWhatapPtotal() != null) gc.setWhatapPtotal(g.getWhatapPtotal());
            if (g.getWhatapPsize() != null) gc.setWhatapPsize(g.getWhatapPsize());
            if (g.getBitbucketUrl() != null) gc.setBitbucketUrl(g.getBitbucketUrl());
            if (g.getBitbucketToken() != null) gc.setBitbucketToken(g.getBitbucketToken());
            if (g.getListRepoLimit() != null) gc.setListRepoLimit(g.getListRepoLimit());
            if (g.getCloneLocalPath() != null) gc.setCloneLocalPath(g.getCloneLocalPath());
            if (g.getGitBashPath() != null) gc.setGitBashPath(g.getGitBashPath());
            if (g.getBlockMonitorWhatapReferer() != null) gc.setBlockMonitorWhatapReferer(g.getBlockMonitorWhatapReferer());
            if (g.getExcludeKeywords() != null && !g.getExcludeKeywords().isEmpty()) {
                String joined = String.join(",", g.getExcludeKeywords().stream()
                        .map(String::trim).filter(s -> !s.isEmpty()).toList());
                if (!joined.isEmpty()) gc.setBotKeywords(joined);
            }

            // teams, 와탭/제니퍼 공통 프로필 JSON 저장
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                if (g.getTeams() != null && !g.getTeams().isEmpty()) {
                    gc.setTeams(om.writeValueAsString(g.getTeams()));
                }
                if (g.getWhatapProfiles() != null && !g.getWhatapProfiles().isEmpty()) {
                    gc.setWhatapProfiles(om.writeValueAsString(g.getWhatapProfiles()));
                }
                if (g.getJenniferProfiles() != null && !g.getJenniferProfiles().isEmpty()) {
                    gc.setJenniferProfiles(om.writeValueAsString(g.getJenniferProfiles()));
                }
            } catch (Exception e) {
                log.warn("[YAML 프로필 직렬화 실패] {}", e.getMessage());
            }
            globalRepo.save(gc);
        }

        // 공통 gitBinPath, 와탭 프로필 맵 준비
        String globalGitBin = (config.getGlobal() != null && config.getGlobal().getGitBinPath() != null)
                ? config.getGlobal().getGitBinPath() : "git";
        Map<String, ReposYamlConfig.WhatapProfile> profileMap = new java.util.HashMap<>();
        if (config.getGlobal() != null && config.getGlobal().getWhatapProfiles() != null) {
            for (ReposYamlConfig.WhatapProfile p : config.getGlobal().getWhatapProfiles()) {
                if (p.getName() != null) profileMap.put(p.getName(), p);
            }
        }
        Map<String, ReposYamlConfig.JenniferProfile> jenniferProfileMap = new java.util.HashMap<>();
        if (config.getGlobal() != null && config.getGlobal().getJenniferProfiles() != null) {
            for (ReposYamlConfig.JenniferProfile p : config.getGlobal().getJenniferProfiles()) {
                if (p.getName() != null) jenniferProfileMap.put(p.getName(), p);
            }
        }

        // 레포별 설정 저장
        List<String> importedNames = new ArrayList<>();
        if (config.getRepositories() != null) {
            for (ReposYamlConfig.RepoEntry entry : config.getRepositories()) {
                if (entry.getRepoName() == null || entry.getRepoName().isBlank()) continue;

                Optional<RepoConfig> existing = repoRepo.findByRepoName(entry.getRepoName());
                RepoConfig rc = existing.orElse(new RepoConfig());
                boolean isNew = rc.getId() == null;

                rc.setRepoName(entry.getRepoName());
                rc.setDomain(entry.getDomain());
                rc.setRootPath(entry.getRootPath());
                // gitBinPath: 레포 개별 지정 > 공통
                rc.setGitBinPath(entry.getGitBinPath() != null && !entry.getGitBinPath().isBlank()
                        ? entry.getGitBinPath() : globalGitBin);
                rc.setGitBranch(entry.getGitBranch());
                rc.setGitPullEnabled(entry.getGitPullEnabled() != null ? entry.getGitPullEnabled() : "Y");
                rc.setAnalysisBatchEnabled(entry.getAnalysisBatchEnabled() != null ? entry.getAnalysisBatchEnabled() : "Y");
                rc.setApmBatchEnabled(entry.getApmBatchEnabled() != null ? entry.getApmBatchEnabled() : "Y");
                rc.setTeamName(entry.getTeamName());
                rc.setManagerName(entry.getManagerName());
                rc.setBusinessName(entry.getBusinessName());
                rc.setApiPathPrefix(entry.getApiPathPrefix());
                rc.setPathConstants(entry.getPathConstants());

                // 프로그램ID별 담당자 매핑 → JSON 변환 (비어있으면 null 저장)
                if (entry.getManagerMappings() != null && !entry.getManagerMappings().isEmpty()) {
                    List<Map<String, String>> items = new ArrayList<>();
                    for (ReposYamlConfig.ManagerMapping mm : entry.getManagerMappings()) {
                        if (mm.getProgramId() == null || mm.getProgramId().isBlank()) continue;
                        if (mm.getManagerName() == null || mm.getManagerName().isBlank()) continue;
                        items.add(Map.of("programId", mm.getProgramId().trim(),
                                         "managerName", mm.getManagerName().trim()));
                    }
                    try {
                        rc.setManagerMappings(items.isEmpty() ? null
                                : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(items));
                    } catch (Exception e) {
                        log.warn("[managerMappings 직렬화 실패] {}", e.getMessage());
                        rc.setManagerMappings(null);
                    }
                } else {
                    rc.setManagerMappings(null);
                }

                if (entry.getWhatap() != null) {
                    ReposYamlConfig.WhatapEntry w = entry.getWhatap();
                    rc.setWhatapEnabled(w.getEnabled());
                    rc.setWhatapProfileName(w.getProfileName());
                    rc.setWhatapPcode(w.getPcode());
                    rc.setWhatapFilter(w.getFilter());
                    rc.setWhatapOkinds(w.getOkinds());
                    rc.setWhatapOkindsName(w.getOkindsName());

                    // 와탭 URL/쿠키: 레포 개별 지정 > 공통 프로필
                    String resolvedUrl = w.getUrl();
                    String resolvedCookie = w.getCookie();
                    if (w.getProfileName() != null && profileMap.containsKey(w.getProfileName())) {
                        ReposYamlConfig.WhatapProfile profile = profileMap.get(w.getProfileName());
                        if (resolvedUrl == null || resolvedUrl.isBlank()) resolvedUrl = profile.getUrl();
                        if (resolvedCookie == null || resolvedCookie.isBlank()) resolvedCookie = profile.getCookie();
                    }
                    rc.setWhatapUrl(resolvedUrl);
                    rc.setWhatapCookie(resolvedCookie);
                }

                // 제니퍼 설정
                if (entry.getJennifer() != null) {
                    ReposYamlConfig.JenniferEntry j = entry.getJennifer();
                    rc.setJenniferEnabled(j.getEnabled());
                    rc.setJenniferProfileName(j.getProfileName());
                    rc.setJenniferSid(j.getSid());
                    rc.setJenniferFilter(j.getFilter());

                    String jUrl = j.getUrl();
                    String jToken = j.getBearerToken();
                    if (j.getProfileName() != null && jenniferProfileMap.containsKey(j.getProfileName())) {
                        ReposYamlConfig.JenniferProfile jp = jenniferProfileMap.get(j.getProfileName());
                        if (jUrl == null || jUrl.isBlank()) jUrl = jp.getUrl();
                        if (jToken == null || jToken.isBlank()) jToken = jp.getBearerToken();
                    }
                    rc.setJenniferUrl(jUrl);
                    rc.setJenniferBearerToken(jToken);

                    // OID 목록 → JSON 저장
                    if (j.getOids() != null && !j.getOids().isEmpty()) {
                        try {
                            List<Map<String, Object>> oidItems = new ArrayList<>();
                            for (ReposYamlConfig.OidEntry o : j.getOids()) {
                                if (o.getOid() == null) continue;
                                Map<String, Object> m = new java.util.LinkedHashMap<>();
                                m.put("oid", o.getOid());
                                m.put("shortName", o.getShortName() != null ? o.getShortName() : "");
                                oidItems.add(m);
                            }
                            rc.setJenniferOids(oidItems.isEmpty() ? null
                                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(oidItems));
                        } catch (Exception e) {
                            log.warn("[jenniferOids 직렬화 실패] {}", e.getMessage());
                            rc.setJenniferOids(null);
                        }
                    } else {
                        rc.setJenniferOids(null);
                    }
                }

                repoRepo.save(rc);
                importedNames.add(entry.getRepoName());
                if (isNew) added++; else updated++;
            }
        }

        log.info("[YAML 임포트 완료] 레포 {}개 발견, 추가={}, 업데이트={}", importedNames.size(), added, updated);

        // Jira 기본 설정 시딩 (DB에 없거나 빈 값인 경우만 채움)
        int jiraMappingsAdded = 0;
        if (config.getJira() != null) {
            ReposYamlConfig.JiraSection jiraYml = config.getJira();

            // jira_config 시딩: 기존 레코드 없으면 생성, 있으면 빈 값만 채움
            JiraConfig jiraCfg = jiraConfigRepo.findFirst().orElse(new JiraConfig());
            boolean jiraCfgChanged = false;
            if (isBlank(jiraCfg.getJiraBaseUrl()) && !isBlank(jiraYml.getBaseUrl())) {
                jiraCfg.setJiraBaseUrl(jiraYml.getBaseUrl()); jiraCfgChanged = true;
            }
            if (isBlank(jiraCfg.getProjectKey()) && !isBlank(jiraYml.getProjectKey())) {
                jiraCfg.setProjectKey(jiraYml.getProjectKey()); jiraCfgChanged = true;
            }
            if (isBlank(jiraCfg.getApiToken()) && !isBlank(jiraYml.getApiToken())) {
                jiraCfg.setApiToken(jiraYml.getApiToken()); jiraCfgChanged = true;
            }
            if (jiraCfgChanged || jiraCfg.getId() == null) {
                jiraConfigRepo.save(jiraCfg);
                log.info("[YAML 임포트] Jira 기본 설정 시딩 완료");
            }

            // jira_user_mapping 시딩: (team, name) 복합키 기준 미존재 시만 생성
            if (jiraYml.getUserMappings() != null) {
                for (ReposYamlConfig.UserMappingEntry entry : jiraYml.getUserMappings()) {
                    if (isBlank(entry.getName())) continue;
                    String team = entry.getTeam() != null ? entry.getTeam() : "";
                    boolean exists = jiraUserMappingRepo
                            .findByTeamNameAndUrlviewerName(team, entry.getName())
                            .isPresent();
                    if (!exists) {
                        JiraUserMapping m = new JiraUserMapping();
                        m.setTeamName(team);
                        m.setUrlviewerName(entry.getName());
                        m.setJiraAccountId(entry.getJiraAccountId() != null ? entry.getJiraAccountId() : "");
                        m.setJiraDisplayName(entry.getJiraDisplayName() != null ? entry.getJiraDisplayName() : "");
                        jiraUserMappingRepo.save(m);
                        jiraMappingsAdded++;
                    }
                }
                log.info("[YAML 임포트] Jira 담당자 매핑 {}건 추가", jiraMappingsAdded);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("added", added);
        result.put("updated", updated);
        result.put("repos", importedNames);
        result.put("jiraMappingsAdded", jiraMappingsAdded);
        return result;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
