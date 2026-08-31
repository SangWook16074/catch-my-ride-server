#!/usr/bin/env bash
# EC2 최초 셋업 (1회, 멱등) — EC2 "위에서" 실행하는 스크립트.
# 로컬에서 한 줄로 실행:
#   scp .env ubuntu@<EC2_IP>:/tmp/app.env && ssh ubuntu@<EC2_IP> 'bash -s' < deploy/bootstrap-ec2.sh
set -euo pipefail

APP_DIR=/opt/catch-my-ride-server
# private 저장소 — EC2는 Deploy Key(읽기 전용)로 접근한다. 클론·CI의 git fetch 둘 다 이 키를 쓴다.
REPO_URL=git@github.com:SangWook16074/catch-my-ride-server.git

# 0. RAM 2GB 미만이면 스왑 2GB (JVM+PostgreSQL 동시 구동 안전판)
if [ ! -f /swapfile ] && [ "$(awk '/MemTotal/{print $2}' /proc/meminfo)" -lt 2000000 ]; then
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
  sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab > /dev/null
  echo "✔ 스왑 2GB 생성"
fi

# 1. git + docker + compose (Ubuntu=apt / Amazon Linux·RHEL=dnf)
if command -v apt-get > /dev/null; then
  command -v git > /dev/null || sudo apt-get install -y -qq git
  if ! command -v docker > /dev/null; then
    sudo apt-get update -qq && sudo apt-get install -y -qq docker.io docker-compose-v2
    echo "✔ docker 설치 (apt)"
  fi
else
  command -v git > /dev/null || sudo dnf install -y -q git
  if ! command -v docker > /dev/null; then
    sudo dnf install -y -q docker
    echo "✔ docker 설치 (dnf)"
  fi
  # Amazon Linux에는 compose·buildx 최신 플러그인 패키지가 없어 직접 설치
  # (/usr/local/lib/docker/cli-plugins가 배포판 기본 경로보다 우선 탐색된다)
  sudo mkdir -p /usr/local/lib/docker/cli-plugins
  if ! docker compose version > /dev/null 2>&1 && ! sudo docker compose version > /dev/null 2>&1; then
    sudo curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose
    sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
    echo "✔ compose 플러그인 설치"
  fi
  # 배포판 동봉 buildx가 낡아 compose build가 거부하는 경우가 있어 최신으로 교체
  if [ ! -f /usr/local/lib/docker/cli-plugins/docker-buildx ]; then
    ARCH=$(uname -m); case "$ARCH" in x86_64) ARCH=amd64;; aarch64) ARCH=arm64;; esac
    BUILDX_URL=$(curl -fsSL https://api.github.com/repos/docker/buildx/releases/latest \
      | grep -oE "https://github.com/docker/buildx/releases/download/[^\"]*linux-$ARCH" | head -1)
    sudo curl -fsSL "$BUILDX_URL" -o /usr/local/lib/docker/cli-plugins/docker-buildx
    sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx
    echo "✔ buildx 플러그인 설치 ($(sudo docker buildx version 2>/dev/null | head -1))"
  fi
fi
sudo systemctl enable --now docker 2>/dev/null || true
sudo usermod -aG docker "$USER" || true

# 2. Deploy Key 준비 — 없으면 생성하고, 저장소 접근이 안 되면 공개키를 출력하고 중단
if [ ! -f ~/.ssh/id_ed25519 ]; then
  ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519 -C "catch-my-ride-server-ec2" -q
fi
ssh-keyscan -t ed25519 github.com 2>/dev/null >> ~/.ssh/known_hosts
if ! git ls-remote -q "$REPO_URL" > /dev/null 2>&1; then
  echo ""
  echo "⛔ 저장소 접근 불가 — 아래 공개키를 GitHub Deploy Key로 등록 후 이 스크립트를 다시 실행하세요:"
  echo "   (등록: gh repo deploy-key add - -R SangWook16074/catch-my-ride-server --title ec2 후 키 붙여넣기)"
  echo ""
  cat ~/.ssh/id_ed25519.pub
  exit 1
fi

# 3. 저장소 클론 — 반드시 현재 사용자로 (root에는 Deploy Key·known_hosts가 없다)
if [ ! -d "$APP_DIR/.git" ]; then
  sudo mkdir -p "$APP_DIR"
  sudo chown "$USER":"$USER" "$APP_DIR"
  git clone "$REPO_URL" "$APP_DIR"
  echo "✔ 클론: $APP_DIR"
fi
sudo chown -R "$USER":"$USER" "$APP_DIR"
cd "$APP_DIR" && git pull -q

# 4. 시크릿 배치 — 로컬에서 scp로 올린 /tmp/app.env 사용
if [ -f /tmp/app.env ]; then
  mv /tmp/app.env "$APP_DIR/.env" && chmod 600 "$APP_DIR/.env"
  echo "✔ .env 배치"
elif [ ! -f "$APP_DIR/.env" ]; then
  echo "⚠ .env 없음 — 키 없이 기동합니다(경고 로그). 나중에 scp로 올리고 app 재기동 필요"
fi

# 5. 전체 스택 기동 (이후 배포는 CI가 app만 갈아끼움)
sudo docker compose up -d --build

# 6. 헬스체크 (최대 2분 — 첫 빌드는 오래 걸릴 수 있음)
for i in $(seq 1 24); do
  if curl -fsS http://127.0.0.1:5000/actuator/health 2>/dev/null | grep -q '"UP"'; then
    echo "✅ 셋업 완료 — app UP"
    exit 0
  fi
  sleep 5
done
echo "❌ 헬스체크 실패 — 로그 확인: sudo docker compose logs app"
exit 1
