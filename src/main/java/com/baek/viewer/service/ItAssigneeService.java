package com.baek.viewer.service;

import com.baek.viewer.model.ItAssignee;
import com.baek.viewer.repository.ItAssigneeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ItAssigneeService {

    private static final Logger log = LoggerFactory.getLogger(ItAssigneeService.class);

    private final ItAssigneeRepository repository;
    private final AuthService authService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public ItAssigneeService(ItAssigneeRepository repository, AuthService authService) {
        this.repository = repository;
        this.authService = authService;
    }

    public static String normalizeTeam(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ");
    }

    public static String normalizeName(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ");
    }

    @Transactional
    public ItAssignee register(String teamName, String assigneeName, String password) {
        String team = normalizeTeam(teamName);
        String name = normalizeName(assigneeName);
        if (team.isEmpty() || name.isEmpty()) throw new IllegalArgumentException("팀명과 담당자명은 필수입니다.");
        if (password == null || password.length() < 4) throw new IllegalArgumentException("비밀번호는 4자 이상이어야 합니다.");
        if (repository.findByTeamNameIgnoreCaseAndAssigneeNameIgnoreCase(team, name).isPresent()) {
            throw new IllegalStateException("이미 등록된 팀·담당자 조합입니다. 로그인을 사용하세요.");
        }
        LocalDateTime now = LocalDateTime.now();
        ItAssignee a = new ItAssignee();
        a.setTeamName(team);
        a.setAssigneeName(name);
        a.setPasswordHash(passwordEncoder.encode(password));
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        ItAssignee saved = repository.save(a);
        log.info("[IT담당자 등록] id={}, team={}, name={}", saved.getId(), team, name);
        return saved;
    }

    public Optional<ItAssignee> login(String teamName, String assigneeName, String password) {
        String team = normalizeTeam(teamName);
        String name = normalizeName(assigneeName);
        if (team.isEmpty() || name.isEmpty() || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        Optional<ItAssignee> opt = repository.findByTeamNameIgnoreCaseAndAssigneeNameIgnoreCase(team, name);
        if (opt.isEmpty()) return Optional.empty();
        ItAssignee a = opt.get();
        if (!passwordEncoder.matches(password, a.getPasswordHash())) return Optional.empty();
        return Optional.of(a);
    }

    public String issueSessionToken(ItAssignee a) {
        return authService.issueEditorToken(a.getId());
    }

    public Page<ItAssignee> listPaged(String teamFilter, String nameFilter, Pageable pageable) {
        String t = teamFilter != null ? teamFilter.trim() : "";
        String n = nameFilter != null ? nameFilter.trim() : "";
        return repository.searchByTeamAndName(t, n, pageable);
    }

    @Transactional
    public void deleteById(long id) {
        repository.deleteById(id);
        log.info("[IT담당자 삭제] id={}", id);
    }

    @Transactional
    public void resetPassword(long id, String newPassword) {
        if (newPassword == null || newPassword.length() < 4) {
            throw new IllegalArgumentException("비밀번호는 4자 이상이어야 합니다.");
        }
        ItAssignee a = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("담당자를 찾을 수 없습니다: " + id));
        a.setPasswordHash(passwordEncoder.encode(newPassword));
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
        log.info("[IT담당자 비밀번호 리셋] id={}", id);
    }

    public Optional<ItAssignee> findById(long id) {
        return repository.findById(id);
    }

    @Transactional
    public void setProposalRejectNotice(long assigneeId, String reason) {
        ItAssignee a = repository.findById(assigneeId).orElse(null);
        if (a == null) {
            log.warn("[제안 반려 알림] 담당자 없음 id={}", assigneeId);
            return;
        }
        a.setProposalRejectNotice(reason);
        a.setProposalRejectNoticeAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
        log.debug("[제안 반려 알림] assigneeId={}", assigneeId);
    }

    @Transactional
    public void clearProposalRejectNotice(long assigneeId) {
        ItAssignee a = repository.findById(assigneeId).orElse(null);
        if (a == null) return;
        a.setProposalRejectNotice(null);
        a.setProposalRejectNoticeAt(null);
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
    }
}
