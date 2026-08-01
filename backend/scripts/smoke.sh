#!/usr/bin/env bash
# =============================================================================
# 任务分配系统后端接口冒烟脚本（覆盖设计文档 §5 全部接口）
# 用法: scripts/smoke.sh [BASE_URL]   默认 http://127.0.0.1:8080
# 特性:
#   - 可重复执行：所有自建数据带唯一时间戳前缀（smoke_<TS>），不修改种子账号/数据
#   - 每步断言 code 字段，输出 [OK]/[FAIL]，结束统计 PASS/FAIL
#   - 依赖: curl + python3（解析 JSON，macOS 自带）
# 注意: 脚本只做接口验证，不执行任何 DELETE/SQL 清理；冒烟产生的
#       smoke_<TS> 前缀数据保留在库中（与种子数据隔离，互不影响）
# =============================================================================
set -u
BASE_URL="${1:-http://127.0.0.1:8080}"
TS=$(date +%s)
PASS=0
FAIL=0
RESP=""

# ---------- 工具函数 ----------
call() { # method token path [data]
  local method=$1 token=$2 path=$3 data=${4:-}
  if [ -n "$data" ]; then
    RESP=$(curl -s -X "$method" "$BASE_URL$path" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$data")
  else
    RESP=$(curl -s -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $token")
  fi
}

code() {
  echo "$RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["code"])' 2>/dev/null || echo "PARSE_ERR"
}

field() { # jsonpath-field；data 为数字（如创建接口返回 id）时直接输出
  echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin)['data']; print(d if isinstance(d,(int,str)) else d['$1'])" 2>/dev/null || echo ""
}

login() { # username password → token
  local body
  body=$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}")
  echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["token"])' 2>/dev/null || echo ""
}

check() { # name expect_code
  local c
  c=$(code)
  if [ "$c" = "$2" ]; then
    echo "  [OK] $1 (code=$c)"; PASS=$((PASS+1))
  else
    echo "  [FAIL] $1, 期望 code=$2, 实际 code=$c, body=$RESP"; FAIL=$((FAIL+1))
  fi
}

echo "========== 任务分配系统接口冒烟 (BASE_URL=$BASE_URL, TS=$TS) =========="

# ---------- 1. 认证 ----------
echo "[1] 认证"
AT=$(login admin admin123);  LT=$(login leader1 123456); ZT=$(login zhangsan 123456)
WT=$(login wangwu 123456)
[ -n "$AT" ] && echo "  ✅ admin 登录" && PASS=$((PASS+1)) || { echo "  ❌ admin 登录失败"; FAIL=$((FAIL+1)); }
call GET "$AT" /api/auth/me; check "GET /auth/me" 0

# ---------- 2. 用户管理（ADMIN） ----------
echo "[2] 用户管理"
SU="smoke_user_$TS"   # 主流程用户（最后保留组内用于删组/删人拒绝场景）
SD="smoke_del_$TS"    # 删除测试专用用户
call POST "$AT" /api/users "{\"username\":\"$SU\",\"password\":\"123456\",\"realName\":\"冒烟用户\",\"role\":\"EMPLOYEE\",\"groupId\":null}"
SUID=$(field id); check "POST /users 创建 $SU" 0
call POST "$AT" /api/users "{\"username\":\"$SD\",\"password\":\"123456\",\"realName\":\"冒烟删除\",\"role\":\"EMPLOYEE\",\"groupId\":null}"
SDID=$(field id); check "POST /users 创建 $SD" 0
RESP=$(curl -s --get "$BASE_URL/api/users" -H "Authorization: Bearer $AT" --data-urlencode "keyword=冒烟" -d "page=1" -d "size=10")
check "GET /users?keyword 分页列表" 0
call GET "$AT" "/api/users?size=-5"; check "GET /users?size=-5 → 400 不 500" 400
call PUT "$AT" "/api/users/$SUID" "{\"username\":\"$SU\",\"password\":\"123456\",\"realName\":\"冒烟用户改\",\"role\":\"EMPLOYEE\",\"groupId\":null}"
check "PUT /users/{id} 修改" 0
call PUT "$AT" "/api/users/$SUID/password" '{"password":"654321"}'; check "PUT /users/{id}/password 重置密码" 0
call POST "$AT" /api/users '{"username":"x","password":"1","realName":"X","role":"EMPLOYEE"}' # 无权场景见权限段
# 无任务记录的用户可删除
call DELETE "$AT" "/api/users/$SDID"; check "DELETE /users/{id} 删除无任务记录用户" 0

# ---------- 3. 小组管理 ----------
echo "[3] 小组管理"
# 动态取组长（leader1）的真实 id，避免写死
RESP=$(curl -s --get "$BASE_URL/api/users" -H "Authorization: Bearer $AT" -d "role=LEADER" -d "page=1" -d "size=10")
LEADER_ID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['records'][0]['id'])" 2>/dev/null)
call POST "$AT" /api/groups "{\"name\":\"smoke组_$TS\",\"leaderId\":$LEADER_ID,\"description\":\"冒烟小组\"}"
GID=$(field id); check "POST /groups 创建小组" 0
call POST "$LT" "/api/groups/$GID/members" "{\"userId\":$SUID}"; check "POST /groups/{id}/members 添加成员" 0
call GET "$LT" "/api/groups/$GID/members"; check "GET /groups/{id}/members 成员列表" 0
call PUT "$LT" "/api/groups/$GID" "{\"name\":\"smoke组_$TS\",\"leaderId\":$LEADER_ID,\"description\":\"冒烟小组改\"}"
check "PUT /groups/{id} 修改小组（组长管自己的组）" 0
call GET "$WT" "/api/groups"; check "GET /groups 列表（含组长名/成员数）" 0
call DELETE "$WT" "/api/groups/$GID"; check "DELETE /groups/{id} 员工删除 → 403" 403

# ---------- 4. 任务 ----------
echo "[4] 任务"
# 个人任务
call POST "$LT" /api/tasks "{\"name\":\"smoke个人任务_$TS\",\"description\":\"冒烟\",\"assignType\":\"INDIVIDUAL\",\"assigneeId\":$SUID}"
T1=$(field id); check "POST /tasks 创建个人任务" 0
# 组任务（组内成员：smoke_user）
call POST "$LT" /api/tasks "{\"name\":\"smoke组任务_$TS\",\"assignType\":\"GROUP\",\"assigneeId\":$GID,\"weights\":[100]}"
T2=$(field id); check "POST /tasks 创建组任务（权重[100]）" 0
call GET "$LT" "/api/tasks?type=mine_created"; check "GET /tasks?type=mine_created" 0
call GET "$ZT" "/api/tasks?type=assigned"; check "GET /tasks?type=assigned" 0
call GET "$LT" "/api/tasks?status=DOING&keyword=smoke"; check "GET /tasks?status&keyword 筛选" 0
call GET "$LT" "/api/tasks/$T1"; check "GET /tasks/{id} 详情（成员/附件）" 0
call PUT "$LT" "/api/tasks/$T1" "{\"name\":\"smoke个人任务_$TS\",\"description\":\"冒烟改\",\"assignType\":\"INDIVIDUAL\",\"assigneeId\":$SUID}"
check "PUT /tasks/{id} 修改任务" 0
call PUT "$LT" "/api/tasks/$T2/weights" "[{\"userId\":$SUID,\"weight\":100}]"
check "PUT /tasks/{id}/weights 调整权重" 0
call PUT "$WT" "/api/tasks/$T1/weights" "[{\"userId\":$SUID,\"weight\":100}]"
check "PUT /tasks/{id}/weights 非分配者 → 403" 403
call POST "$ZT" /api/tasks "{\"name\":\"smoke越权_$TS\",\"assignType\":\"INDIVIDUAL\",\"assigneeId\":$SUID}"
check "POST /tasks 员工创建 → 403" 403

# ---------- 5. 附件 ----------
echo "[5] 附件"
echo "冒烟附件内容-$TS" > /tmp/smoke_attach_$TS.txt
RESP=$(curl -s -X POST "$BASE_URL/api/files/upload" -H "Authorization: Bearer $LT" -F "file=@/tmp/smoke_attach_$TS.txt")
FID=$(field id); check "POST /files/upload 上传" 0
call DELETE "$LT" "/api/files/$FID"; check "DELETE /files/{id} 删除附件记录" 0
rm -f /tmp/smoke_attach_$TS.txt
# 无 file 参数 → 400
RESP=$(curl -s -X POST "$BASE_URL/api/files/upload" -H "Authorization: Bearer $LT")
check "POST /files/upload 缺 file 参数 → 400" 400

# ---------- 6. 汇报与审核 ----------
echo "[6] 汇报与审核"
ST=$(login "$SU" 654321)   # smoke_user 密码已被重置为 654321
call POST "$ST" "/api/tasks/$T1/reports" '{"content":"冒烟汇报编码完成-$TS","progress":50}'
R1=$(field id); check "POST /tasks/{id}/reports 提交汇报" 0
call GET "$LT" /api/reports/pending; check "GET /reports/pending 待我审核" 0
call POST "$LT" "/api/reports/$R1/approve" '{"progress":60,"reviewComment":"冒烟通过"}'
check "POST /reports/{id}/approve 通过（手动调进度60）" 0
call POST "$LT" "/api/reports/$R1/approve" '{}'
check "POST /reports/{id}/approve 重复审核 → 400" 400
# 第二条：驳回（理由必填）
call POST "$ST" "/api/tasks/$T1/reports" '{"content":"冒烟汇报联调-$TS","progress":70}'
R2=$(field id); check "POST /tasks/{id}/reports 第二次汇报" 0
call POST "$LT" "/api/reports/$R2/reject" '{}'
check "POST /reports/{id}/reject 空理由 → 400" 400
call POST "$LT" "/api/reports/$R2/reject" '{"reviewComment":"冒烟驳回理由"}'
check "POST /reports/{id}/reject 驳回（带理由）" 0
# 可见性：非审核人 wangwu 看不到 REJECTED 汇报
call GET "$WT" "/api/tasks/$T1/reports"; check "GET /tasks/{id}/reports 第三方（仅 APPROVED）" 0
call GET "$LT" "/api/tasks/$T1/reports"; check "GET /tasks/{id}/reports 审核人（含审核详情）" 0
# 进度校验：低于当前进度 → 400
call POST "$ST" "/api/tasks/$T1/reports" '{"content":"进度回退测试","progress":10}'
check "POST /tasks/{id}/reports 进度回退 → 400" 400

# ---------- 7. 评论 ----------
echo "[7] 评论"
call POST "$WT" "/api/tasks/$T1/comments" '{"content":"冒烟任务评论-$TS"}'
C1=$(field id); check "POST /tasks/{id}/comments 任务评论" 0
call GET "$WT" "/api/tasks/$T1/comments"; check "GET /tasks/{id}/comments 任务评论列表" 0
call POST "$WT" "/api/comments/$C1/reply" '{"content":"冒烟回复-$TS"}'
check "POST /comments/{id}/reply 回复顶级评论" 0
# 对 APPROVED 汇报（R1）评论
call POST "$WT" "/api/reports/$R1/comments" '{"content":"冒烟汇报评论-$TS"}'
check "POST /reports/{id}/comments 评论 APPROVED 汇报" 0
call GET "$WT" "/api/reports/$R1/comments"; check "GET /reports/{id}/comments 查看 APPROVED 汇报评论" 0
# 对 REJECTED 汇报（R2）评论 → 403
call POST "$WT" "/api/reports/$R2/comments" '{"content":"越权评论-$TS"}'
check "POST /reports/{id}/comments 评论 REJECTED 汇报 → 403" 403
call GET "$WT" "/api/reports/$R2/comments"; check "GET /reports/{id}/comments 查看 REJECTED 汇报评论 → 403" 403
# 回复不能回复回复
call POST "$WT" "/api/comments/$C1/reply" '{"content":"第二层冒烟回复-$TS-$TS"}'
REPLY1=$(field id)
call POST "$WT" "/api/comments/$REPLY1/reply" '{"content":"第三层-$TS"}'
check "POST /comments/{id}/reply 回复回复 → 400" 400

# ---------- 8. 删除保护 ----------
echo "[8] 删除保护"
call DELETE "$AT" "/api/groups/$GID"; check "DELETE /groups/{id} 组内有未完成任务 → 400" 400
call DELETE "$AT" "/api/users/$SUID"; check "DELETE /users/{id} 用户有任务记录 → 400" 400
call DELETE "$LT" "/api/tasks/$T2"; check "DELETE /tasks/{id} 组任务删除" 0
call DELETE "$LT" "/api/tasks/$T1"; check "DELETE /tasks/{id} 个人任务删除" 0
call DELETE "$LT" "/api/tasks/$T1"; check "DELETE /tasks/{id} 已删除任务 → 400" 400

echo ""
echo "========== 结果: PASS=$PASS FAIL=$FAIL =========="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
