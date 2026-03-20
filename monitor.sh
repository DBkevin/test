#!/bin/bash
REPO="DBkevin/test"
TOKEN="$1"
MAX_WAIT=1200
INTERVAL=30

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
    
    RESPONSE=$(curl -s -H "Authorization: token $TOKEN" \
        "https://api.github.com/repos/$REPO/actions/runs?per_page=1")
    
    STATUS=$(echo "$RESPONSE" | grep -o '"status":"[^"]*"' | head -1 | cut -d'"' -f4)
    CONCLUSION=$(echo "$RESPONSE" | grep -o '"conclusion":"[^"]*"' | head -1 | cut -d'"' -f4)
    RUN_ID=$(echo "$RESPONSE" | grep -o '"id":[0-9]*' | head -1 | cut -d':' -f2)
    
    if [ -n "$RUN_ID" ]; then
        case "$STATUS" in
            "queued") echo "⏳ 等待中... ($(($ELAPSED/60)) 分钟)" ;;
            "in_progress") echo "🔨 编译中... ($(($ELAPSED/60)) 分钟)" ;;
            "completed")
                echo ""
                case "$CONCLUSION" in
                    "success")
                        echo "✅ 编译成功！"
                        echo "📦 下载：https://github.com/$REPO/actions/runs/$RUN_ID"
                        exit 0 ;;
                    "failure")
                        echo "❌ 编译失败！"
                        echo "日志：https://github.com/$REPO/actions/runs/$RUN_ID"
                        exit 1 ;;
                    *) echo "⚠️ 状态：$CONCLUSION"; exit 1 ;;
                esac ;;
            *) echo "⏳ 等待触发... ($(($ELAPSED/60)) 分钟)" ;;
        esac
    else
        echo "⏳ 等待触发... ($(($ELAPSED/60)) 分钟)"
    fi
    
    sleep $INTERVAL
done
