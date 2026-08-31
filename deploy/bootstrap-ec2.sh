#!/usr/bin/env bash
# EC2 최초 셋업 (1회, 멱등) — EC2 "위에서" 실행하는 스크립트.
# 로컬에서 한 줄로 실행:
#   scp .env ubuntu@<EC2_IP>:/tmp/app.env && ssh ubuntu@<EC2_IP> 'bash -s' < deploy/bootstrap-ec2.sh
set -euo pipefail

APP_DIR=/opt/catch-my-ride-server
REPO_URL=https://github.com/SangWook16074/catch-my-ride-server.git

# 0. RAM 2GB 미만이면 스왑 2GB (JVM+PostgreSQL 동시 구동 안전판)
if [ ! -f /swapfile ] && [ "$(awk '/MemTotal/{print $2}' /proc/meminfo)" -lt 2000000 ]; then
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
  sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab > /dev/null
  echo "✔ 스왑 2GB 생성"
fi

# 1. docker + compose
if ! command -v docker > /dev/null; then
  sudo apt-get update -qq && sudo apt-get install -y -qq docker.io docker-compose-v2
  echo "✔ docker 설치"
fi
sudo usermod -aG docker "$USER" || true

# 2. 저장소 클론 (private 저장소 — https 클론에 GitHub 인증이 필요하면 토큰 포함 URL로 바꿔 실행)
if [ ! -d "$APP_DIR/.git" ]; then
  sudo git clone "$REPO_URL" "$APP_DIR"
  echo "✔ 클론: $APP_DIR"
fi
sudo chown -R "$USER":"$USER" "$APP_DIR"
cd "$APP_DIR" && git pull -q

# 3. 시크릿 배치 — 로컬에서 scp로 올린 /tmp/app.env 사용
if [ -f /tmp/app.env ]; then
  mv /tmp/app.env "$APP_DIR/.env" && chmod 600 "$APP_DIR/.env"
  echo "✔ .env 배치"
elif [ ! -f "$APP_DIR/.env" ]; then
  echo "⚠ .env 없음 — 키 없이 기동합니다(경고 로그). 나중에 scp로 올리고 app 재기동 필요"
fi

# 4. 전체 스택 기동 (이후 배포는 CI가 app만 갈아끼움)
sudo docker compose up -d --build

# 5. 헬스체크 (최대 2분 — 첫 빌드는 오래 걸릴 수 있음)
for i in $(seq 1 24); do
  if curl -fsS http://127.0.0.1:5000/actuator/health 2>/dev/null | grep -q '"UP"'; then
    echo "✅ 셋업 완료 — app UP"
    exit 0
  fi
  sleep 5
done
echo "❌ 헬스체크 실패 — 로그 확인: sudo docker compose logs app"
exit 1
