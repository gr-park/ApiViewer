package com.baek.viewer.service;

import com.baek.viewer.repository.ApiRecordRepository;
import com.baek.viewer.repository.RepoConfigRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TeamSuggestionService {

    private static final int MAX_RESULTS = 50;
    private static final int MAX_QUERY_LEN = 40;

    private final RepoConfigRepository repoConfigRepository;
    private final ApiRecordRepository apiRecordRepository;

    public TeamSuggestionService(RepoConfigRepository repoConfigRepository, ApiRecordRepository apiRecordRepository) {
        this.repoConfigRepository = repoConfigRepository;
        this.apiRecordRepository = apiRecordRepository;
    }

    public List<String> suggestTeams(String qRaw) {
        String q = qRaw == null ? "" : qRaw.trim().toLowerCase(Locale.ROOT);
        if (q.length() > MAX_QUERY_LEN) q = q.substring(0, MAX_QUERY_LEN);
        Set<String> out = new LinkedHashSet<>();
        for (String t : repoConfigRepository.findDistinctTeamNames()) {
            if (t == null || t.isBlank()) continue;
            if (q.isEmpty() || t.toLowerCase(Locale.ROOT).contains(q)) out.add(t.trim());
            if (out.size() >= MAX_RESULTS) return List.copyOf(out);
        }
        for (String t : apiRecordRepository.findDistinctTeamOverrides()) {
            if (t == null || t.isBlank()) continue;
            if (q.isEmpty() || t.toLowerCase(Locale.ROOT).contains(q)) out.add(t.trim());
            if (out.size() >= MAX_RESULTS) return List.copyOf(out);
        }
        return out.stream().sorted(String::compareToIgnoreCase).limit(MAX_RESULTS).collect(Collectors.toList());
    }
}
