# deploy/ — 배포 절차

> 기획·클라이언트는 [nochijima](https://github.com/SangWook16074/nochijima) 저장소, 이 저장소는 서버 단독.
> 배포 대상: 기존 보유 EC2(IP는 GitHub Secret EC2_HOST) + ECR 이미지 관리 + GitHub Actions 자동 배포.

## EC2 최초 셋업 (1회)

```bash
# 0. (t3.micro 등 RAM 1GB) 스왑 2GB 생성: JVM+PostgreSQL 동시 구동의 안전판
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# 1. docker + compose 플러그인 설치 (Ubuntu 기준)
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2

# 2. 저장소 클론 (CI가 git checkout을 실행하므로 SSH 사용자 소유여야 함)
sudo git clone https://github.com/SangWook16074/catch-my-ride-server.git /opt/catch-my-ride-server
sudo chown -R ec2-user:ec2-user /opt/catch-my-ride-server
cd /opt/catch-my-ride-server

# 3. 시크릿 배치 (템플릿: .env.example — 키 관리 방침은 그 파일 주석 참조)
cp .env.example .env && vi .env   # 실값 입력
# RAM 1GB라면 힙 축소 옵션도 추가: JAVA_OPTS=-Xms128m -Xmx384m
chmod 600 .env

# 4. 전체 스택 기동 (CI는 이후 app만 갈아끼운다 — postgres·caddy는 이 최초 기동이 만든다)
docker compose up -d --build

# 5. 확인
curl -fsS http://127.0.0.1:5000/actuator/health   # {"status":"UP"}
docker compose logs -f app                         # 키 없으면 경고 로그 확인
```

- 보안그룹 인바운드: 80·443 전체 공개(HTTPS + 인증서 발급), 22는 SSH용(CI 배포도 22 사용), **5000은 열지 않는다**
- HTTPS는 DNS A 레코드(`catchmyride.hansw.dev` → EC2 탄력적 IP)가 연결되면 Caddy가 자동 발급

## 평상시 배포 — CI/CD (release 태그)

release 브랜치 위 커밋에 `v*` 태그를 푸시하면 [.github/workflows/deploy.yml](../.github/workflows/deploy.yml)이 자동 배포한다:
테스트 → Docker 이미지 빌드 → **ECR 푸시** → SSH로 EC2에서 pull + app만 재기동(postgres·caddy 유지) → 헬스체크.

```bash
# 릴리스 절차
git checkout release && git merge main && git push origin release
git tag v0.1.0 && git push origin v0.1.0
```

### 최초 1회 설정

1. **ECR 리포지토리 생성**:
   ```bash
   aws ecr create-repository --repository-name catch-my-ride-server --region ap-northeast-2
   ```
2. **배포용 IAM 사용자** — ECR push 최소 권한: `ecr:GetAuthorizationToken`(리소스 `*`) + 해당 리포지토리에 `ecr:BatchCheckLayerAvailability`, `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `ecr:PutImage`
3. **GitHub Secrets** 등록:
   ```bash
   gh secret set AWS_ACCESS_KEY_ID -R SangWook16074/catch-my-ride-server
   gh secret set AWS_SECRET_ACCESS_KEY -R SangWook16074/catch-my-ride-server
   gh secret set EC2_HOST -R SangWook16074/catch-my-ride-server --body "<EC2 공인 IP>"
   gh secret set EC2_USER -R SangWook16074/catch-my-ride-server --body "ec2-user"
   gh secret set EC2_SSH_KEY -R SangWook16074/catch-my-ride-server < ~/.ssh/<EC2키>.pem
   ```
   선택 Variables: `AWS_REGION`(기본 ap-northeast-2), `ECR_REPOSITORY`(기본 catch-my-ride-server), `EC2_APP_DIR`(기본 /opt/catch-my-ride-server)

EC2가 Graviton(arm64)이면 deploy.yml의 `platforms: linux/amd64`를 `linux/arm64`로 변경.

**롤백**: 이전 태그로 워크플로를 다시 실행하거나(Actions → 해당 태그 run → Re-run), EC2에서 직접:
```bash
cd /opt/catch-my-ride-server
echo "APP_IMAGE=<레지스트리>/catch-my-ride-server:v이전버전" > .deploy.env
docker compose --env-file .deploy.env -f docker-compose.yml -f docker-compose.prod.yml up -d --no-build app
```

### 수동 배포 (CI 우회, 비상시)

```bash
cd /opt/catch-my-ride-server
git pull
docker compose up -d --build app   # app만 재빌드·재기동 (postgres·caddy는 유지)
```

## 로컬 개발 확인

```bash
docker compose up -d app postgres   # caddy 제외
curl -fsS http://127.0.0.1:5000/actuator/health
docker compose down                 # 정리
```

## 운영

- **백업**: `deploy/backup-db.sh` — crontab에 `0 4 * * *`로 등록 (스크립트 상단 주석 참조). 30일 지난 백업 자동 삭제
- **감시**: UptimeRobot 무료 플랜에 `https://catchmyride.hansw.dev/actuator/health` 등록
- **로그**: `docker compose logs -f app` / 폴링 JSONL은 호스트 `./spike-logs/`에 쌓임
- **Swagger**: `https://catchmyride.hansw.dev/swagger-ui.html` (스펙: `/openapi.yaml`)
- **Redis 켜기** (인스턴스 2대 이상이 된 시점에만): `docker compose --profile cache up -d`
