#!/bin/sh
# ApiViewer 실행 스크립트
# 빌드 후 실행: sh run.sh
# 빌드 없이 실행 (JAR 이미 있을 때): sh run.sh --no-build

MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
JAR="target/api-viewer-1.0.0.jar"

cd "$(dirname "$0")"

if [ "$1" != "--no-build" ]; then
  echo "[INFO] 빌드 중..."
  "$MVN" -q package -DskipTests 2>&1 | grep -v "sun.misc"
fi

if [ ! -f "$JAR" ]; then
  echo "[ERROR] JAR 파일이 없습니다. 먼저 빌드하세요."
  exit 1
fi

echo ""
echo "================================================"
echo "  API Viewer 시작"
echo "------------------------------------------------"
echo "  📊 대시보드      : http://localhost:8080/"
echo "  📋 URL분석현황   : http://localhost:8080/viewer.html"
echo "  📈 URL호출현황   : http://localhost:8080/call-stats.html"
echo "  📝 현업검토     : http://localhost:8080/review.html"
echo "  🚧 차단모니터링  : http://localhost:8080/url-block-monitor.html"
echo "  🗺️  업무플로우   : http://localhost:8080/workflow.html"
echo "  ⚙️  설정         : http://localhost:8080/settings.html"
echo "  🔍 URL분석(추출) : http://localhost:8080/extract.html"
echo "------------------------------------------------"
echo "  🗄  H2 콘솔      : http://localhost:8080/h2-console"
echo "    JDBC URL      : jdbc:h2:file:./data/api-viewer-db"
echo "    User / Pass   : sa / (없음)"
echo "================================================"
echo "  종료: Ctrl+C"
echo "================================================"
echo ""
java -Djava.net.preferIPv4Stack=true -jar "$JAR"
