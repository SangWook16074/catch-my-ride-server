#!/usr/bin/env bash
# CI/CD용 AWS 리소스 + GitHub Secrets 셋업 (1회, 멱등) — "로컬 Mac에서" 실행.
# 선행: aws configure 완료(관리자 권한 자격증명), gh 로그인.
# 사용법: deploy/setup-aws-cicd.sh <EC2 SSH 개인키(.pem) 경로>
set -euo pipefail

PEM="${1:?사용법: setup-aws-cicd.sh <EC2 SSH 개인키(.pem) 경로>}"
[ -f "$PEM" ] || { echo "❌ 키 파일 없음: $PEM"; exit 1; }

REGION=ap-northeast-2
ECR_REPO=catch-my-ride-server
IAM_USER=catch-my-ride-deploy
GH_REPO=SangWook16074/catch-my-ride-server
EC2_HOST=3.37.219.135
EC2_USER=ec2-user

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "AWS 계정: $ACCOUNT_ID / 리전: $REGION"

# 1. ECR 리포지토리
if ! aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$REGION" > /dev/null 2>&1; then
  aws ecr create-repository --repository-name "$ECR_REPO" --region "$REGION" > /dev/null
  echo "✔ ECR 리포지토리 생성: $ECR_REPO"
else
  echo "✔ ECR 리포지토리 존재: $ECR_REPO"
fi

# 2. 배포용 IAM 사용자 + ECR push 최소 권한
if ! aws iam get-user --user-name "$IAM_USER" > /dev/null 2>&1; then
  aws iam create-user --user-name "$IAM_USER" > /dev/null
  echo "✔ IAM 사용자 생성: $IAM_USER"
fi
aws iam put-user-policy --user-name "$IAM_USER" --policy-name ecr-push --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    { \"Effect\": \"Allow\", \"Action\": \"ecr:GetAuthorizationToken\", \"Resource\": \"*\" },
    { \"Effect\": \"Allow\",
      \"Action\": [\"ecr:BatchCheckLayerAvailability\",\"ecr:BatchGetImage\",\"ecr:GetDownloadUrlForLayer\",
                 \"ecr:InitiateLayerUpload\",\"ecr:UploadLayerPart\",\"ecr:CompleteLayerUpload\",\"ecr:PutImage\"],
      \"Resource\": \"arn:aws:ecr:$REGION:$ACCOUNT_ID:repository/$ECR_REPO\" }
  ]
}"
echo "✔ ECR push 정책 부착"

# 3. 액세스 키 발급 (기존 키 2개면 발급 불가 — 가장 오래된 것 삭제 후 재발급하려면 수동 정리)
KEY_JSON=$(aws iam create-access-key --user-name "$IAM_USER" --query AccessKey --output json)
AKID=$(echo "$KEY_JSON" | python3 -c "import sys,json;print(json.load(sys.stdin)['AccessKeyId'])")
SECRET=$(echo "$KEY_JSON" | python3 -c "import sys,json;print(json.load(sys.stdin)['SecretAccessKey'])")
echo "✔ 액세스 키 발급: $AKID"

# 4. GitHub Secrets
gh secret set AWS_ACCESS_KEY_ID     -R "$GH_REPO" --body "$AKID"
gh secret set AWS_SECRET_ACCESS_KEY -R "$GH_REPO" --body "$SECRET"
gh secret set EC2_HOST              -R "$GH_REPO" --body "$EC2_HOST"
gh secret set EC2_USER              -R "$GH_REPO" --body "$EC2_USER"
gh secret set EC2_SSH_KEY           -R "$GH_REPO" < "$PEM"
echo "✔ GitHub Secrets 5개 등록 완료"

echo ""
echo "다음 단계 — 릴리스 배포:"
echo "  git checkout release && git merge main && git push origin release"
echo "  git tag v0.1.0 && git push origin v0.1.0"
