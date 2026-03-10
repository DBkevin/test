#!/bin/bash

# GitHub Actions 编译监控脚本
# 使用方法：./monitor-build.sh

REPO="DBkevin/minishop"
TOKEN="${GITHUB_TOKEN:-}"  # 从环境变量读取，或手动填入
MAX_WAIT=1200  # 最多等 20 分钟
INTERVAL=30    # 每 30 秒检查一次

echo "🦞 开始监控编译进度..."
echo "仓库：https://github.com/$REPO/actions"
echo ""

START_TIME=$(date +%s)

while true; do
    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))
    
    if [ $ELAPSED -ge $MAX_WAIT ]; then
        echo "❌ 超时！编译超过 20 分钟未完成"
        echo "请手动检查：https://github.com/$REPO/actions"
        exit 1
    fi
    
    # 获取最近的 workflow runs
    RESPONSE=$(curl -s -H "Authorization: token $TOKEN" \
        "https://api.github.com/repos/$REPO/actions/runs?per_page=1")
    
    STATUS=$(echo "$RESPONSE" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    CONCLUSION=$(echo "$RESPONSE" | grep -o '"conclusion":"[^"]*"' | head -1 | cut -d'"' -f4)
    RUN_ID=$(echo "$RESPONSE" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)
    
    if [ -n "$RUN_ID" ]; then
        case "$STATUS" in
            "queued")
                echo "⏳ 等待中... ($(($ELAPSED/60)) 分钟)"
                ;;
            "in_progress")
                echo "🔨 编译中... ($(($ELAPSED/60)) 分钟)"
                ;;
            "completed")
                echo ""
                case "$CONCLUSION" in
                    "success")
                        echo "✅ 编译成功！"
                        echo ""
                        echo "📦 下载 APK:"
                        echo "   1. 访问：https://github.com/$REPO/actions/runs/$RUN_ID"
                        echo "   2. 滚动到 Artifacts 区域"
                        echo "   3. 点击 'app-debug' 下载"
                        echo ""
                        echo "或直接访问：https://github.com/$REPO/actions/runs/$RUN_ID"
                        exit 0
                        ;;
                    "failure")
                        echo "❌ 编译失败！"
                        echo "查看日志：https://github.com/$REPO/actions/runs/$RUN_ID"
                        exit 1
                        ;;
                    "cancelled")
                        echo "⛔ 编译被取消"
                        exit 1
                        ;;
                    *)
                        echo "⚠️ 未知状态：$CONCLUSION"
                        exit 1
                        ;;
                esac
                ;;
            *)
                echo "⏳ 等待 Actions 触发... ($(($ELAPSED/60)) 分钟)"
                ;;
        esac
    else
        echo "⏳ 等待 Actions 触发... ($(($ELAPSED/60)) 分钟)"
    fi
    
    sleep $INTERVAL
done
