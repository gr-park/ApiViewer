package com.baek.viewer.ai;

import com.baek.viewer.model.AiPromptTemplate;

/** 기동 시 slug 별 기본 템플릿 (DB에 없을 때만 삽입) */
public final class AiPromptTemplateDefaults {

    private AiPromptTemplateDefaults() {}

    public static AiPromptTemplate menuInference() {
        AiPromptTemplate t = new AiPromptTemplate();
        t.setSlug(AiPromptSlugs.MENU_INFERENCE);
        t.setTitle("관련 메뉴(현업 설명) 추론");
        t.setBody("""
                다음은 사내 API URL과 소스에서 추출한 주석·어노테이션 정보입니다. 비IT 현업 담당자가 이해하기 쉬운 업무 메뉴명 또는 기능을 한 줄로만 제시하세요. 확신이 없으면 문장 끝에 (추정)을 붙이세요.

                업무명: {{business_name}}
                레포지토리: {{repository_name}}
                HTTP: {{http_method}}  경로: {{api_path}}
                Full URL: {{full_url}}

                - ApiOperation / Swagger 요약: {{source_api_operation}}
                - Description 태그: {{source_description_tag}}
                - 메소드 JavaDoc 요약: {{source_method_javadoc}}
                - 컨트롤러 클래스 JavaDoc 요약: {{source_controller_javadoc}}
                - 메소드 매핑 속성: {{source_request_property}}
                - 컨트롤러 매핑 속성: {{source_controller_request_property}}

                응답 본문에는 메뉴/기능 설명 한 줄만 출력하세요. 다른 설명은 쓰지 마세요.
                """);
        t.setEnabled(true);
        return t;
    }

    public static AiPromptTemplate opsDigest() {
        AiPromptTemplate t = new AiPromptTemplate();
        t.setSlug(AiPromptSlugs.OPS_DIGEST);
        t.setTitle("분석·배치 현황 요약 알림");
        t.setBody("""
                다음은 URL Viewer 시스템의 최근 배치 실행 이력(JSON)입니다. 관리자가 오늘 확인하면 좋은 포인트를 한국어로 3줄 이내 불릿(- 로 시작)으로 요약하세요. 과장 없이 사실만 반영하세요.
                중요한 실패·지연·이상 징후는 HTML 인라인 태그로 강조하세요.
                허용 태그만 사용: <strong>, <em>, <mark>, <b>, <i> (속성·다른 태그·스크립트 금지).

                {{recent_batch_logs_json}}
                """);
        t.setEnabled(true);
        return t;
    }
}
