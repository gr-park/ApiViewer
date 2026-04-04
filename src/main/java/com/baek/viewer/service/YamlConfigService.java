package com.baek.viewer.service;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.RepoConfig;
import com.baek.viewer.model.ReposYamlConfig;
import com.baek.viewer.repository.GlobalConfigRepository;
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

    @Value("${api.viewer.repos-config-path:./repos-config.yml}")
    private String defaultConfigPath;

    public YamlConfigService(RepoConfigRepository repoRepo, GlobalConfigRepository globalRepo) {
        this.repoRepo = repoRepo;
        this.globalRepo = globalRepo;
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

    public Map<String, Object> importFromYamlContent(String content) throws Exception {
        log.info("[YAML 내용 임포트] 크기={}bytes", content.length());
        Yaml yaml = new Yaml(new Constructor(ReposYamlConfig.class, new LoaderOptions()));
        ReposYamlConfig config = yaml.load(content);
        if (config == null) throw new IllegalArgumentException("YAML 내용이 비어 있습니다.");
        return processYamlConfig(config);
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
            globalRepo.save(gc);
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
                rc.setGitBinPath(entry.getGitBinPath());
                rc.setTeamName(entry.getTeamName());
                rc.setManagerName(entry.getManagerName());
                rc.setBusinessName(entry.getBusinessName());
                rc.setApiPathPrefix(entry.getApiPathPrefix());
                rc.setPathConstants(entry.getPathConstants());

                if (entry.getWhatap() != null) {
                    ReposYamlConfig.WhatapEntry w = entry.getWhatap();
                    rc.setWhatapEnabled(w.getEnabled());
                    rc.setWhatapUrl(w.getUrl());
                    rc.setWhatapPcode(w.getPcode());
                    rc.setWhatapFilter(w.getFilter());
                    rc.setWhatapOkinds(w.getOkinds());
                    rc.setWhatapOkindsName(w.getOkindsName());
                    rc.setWhatapCookie(w.getCookie());
                }

                repoRepo.save(rc);
                importedNames.add(entry.getRepoName());
                if (isNew) added++; else updated++;
            }
        }

        log.info("[YAML 임포트 완료] 레포 {}개 발견, 추가={}, 업데이트={}", importedNames.size(), added, updated);

        Map<String, Object> result = new HashMap<>();
        result.put("added", added);
        result.put("updated", updated);
        result.put("repos", importedNames);
        return result;
    }
}
