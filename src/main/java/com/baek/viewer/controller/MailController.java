package com.baek.viewer.controller;

import com.baek.viewer.model.GlobalConfig;
import com.baek.viewer.model.MailTemplate;
import com.baek.viewer.repository.GlobalConfigRepository;
import com.baek.viewer.repository.MailTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/mail")
public class MailController {

    private static final Logger log = LoggerFactory.getLogger(MailController.class);

    private final GlobalConfigRepository globalConfigRepo;
    private final MailTemplateRepository templateRepo;

    public MailController(GlobalConfigRepository globalConfigRepo, MailTemplateRepository templateRepo) {
        this.globalConfigRepo = globalConfigRepo;
        this.templateRepo = templateRepo;
    }

    // ── 서식 CRUD ──

    @GetMapping("/templates")
    public ResponseEntity<?> listTemplates() {
        return ResponseEntity.ok(templateRepo.findAllByOrderByUpdatedAtDesc());
    }

    @PostMapping("/templates")
    public ResponseEntity<?> createTemplate(@RequestBody MailTemplate t) {
        log.info("[메일 서식] 생성: {}", t.getTemplateName());
        return ResponseEntity.ok(templateRepo.save(t));
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<?> updateTemplate(@PathVariable Long id, @RequestBody MailTemplate body) {
        var t = templateRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("서식 없음: " + id));
        t.setTemplateName(body.getTemplateName());
        t.setSubject(body.getSubject());
        t.setBody(body.getBody());
        return ResponseEntity.ok(templateRepo.save(t));
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<?> deleteTemplate(@PathVariable Long id) {
        templateRepo.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    // ── 메일 발송 ──

    @PostMapping("/send")
    public ResponseEntity<?> sendMail(@RequestBody Map<String, Object> body) {
        String to = (String) body.get("to");
        String subject = (String) body.get("subject");
        String content = (String) body.get("body");

        if (to == null || to.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "수신자를 입력하세요."));
        if (subject == null || subject.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "제목을 입력하세요."));

        GlobalConfig gc = globalConfigRepo.findById(1L).orElse(new GlobalConfig());
        if (gc.getSmtpHost() == null || gc.getSmtpHost().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SMTP 서버가 설정되지 않았습니다. 공통설정에서 SMTP 정보를 입력하세요."));
        }

        try {
            JavaMailSender sender = buildMailSender(gc);
            String[] recipients = to.split("[,;\\s]+");

            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(gc.getMailFrom() != null && !gc.getMailFrom().isBlank() ? gc.getMailFrom() : "apiviewer@localhost");
            msg.setTo(recipients);
            msg.setSubject(subject);
            msg.setText(content != null ? content : "");

            sender.send(msg);
            log.info("[메일 발송] 수신자={}, 제목={}", to, subject);
            return ResponseEntity.ok(Map.of("success", true, "recipients", recipients.length, "subject", subject));
        } catch (Exception e) {
            log.error("[메일 발송 실패] {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "발송 실패: " + e.getMessage()));
        }
    }

    /** DB에 저장된 SMTP 설정으로 JavaMailSender를 동적 생성 */
    private JavaMailSender buildMailSender(GlobalConfig gc) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(gc.getSmtpHost());
        sender.setPort(gc.getSmtpPort());
        if (gc.getSmtpUsername() != null && !gc.getSmtpUsername().isBlank()) {
            sender.setUsername(gc.getSmtpUsername());
            sender.setPassword(gc.getSmtpPassword());
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.connectiontimeout", "5000");
        props.put("mail.smtp.timeout", "5000");
        // 포트 587이면 STARTTLS 활성화
        if (gc.getSmtpPort() == 587) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.auth", "true");
        }
        return sender;
    }
}
