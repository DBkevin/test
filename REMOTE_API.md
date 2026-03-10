# 远程指令 API 文档

## 概述

手机端通过 HTTP 轮询接收远程指令，实现医院列表下发和抓取控制。

---

## 基础配置

### 服务端地址

**默认**: `http://192.168.1.100:8080`

**配置位置**: APP 设置 → 远程配置 → 服务器地址

### 轮询间隔

**默认**: `5 秒`

---

## API 接口

### 1. 轮询指令

**请求**:
```
GET /api/command/poll?device_id={device_id}
```

**响应**:
```json
{
  "type": "hospital_list",
  "data": {
    "hospitals": [
      "北京协和医院",
      "上海九院",
      "广州南方医院"
    ]
  },
  "timestamp": 1710072000000
}
```

**指令类型**:

| type | 说明 | data 字段 |
|------|------|----------|
| `hospital_list` | 下发医院列表 | `hospitals`: 医院名称数组 |
| `start_capture` | 开始抓取 | 无 |
| `stop_capture` | 停止抓取 | 无 |
| `update_config` | 更新配置 | `config`: 配置键值对 |

---

### 2. 上报任务结果

**请求**:
```
POST /api/command/result
Content-Type: application/json

{
  "device_id": "device_1710072000000",
  "task_id": 0,
  "hospital_name": "北京协和医院",
  "status": "COMPLETED",
  "data": {
    "hospital_name": "北京协和医院",
    "honors": "中国整形美容协会认证单位",
    "group_buys": [
      {
        "title": "黄金微针体验",
        "price": "¥999",
        "sales": "已售 1000+"
      }
    ]
  },
  "timestamp": 1710072300000
}
```

**响应**:
```json
{
  "code": 200,
  "message": "success"
}
```

---

## 使用示例

### Python 服务端示例

```python
from flask import Flask, request, jsonify
import json

app = Flask(__name__)

# 存储设备状态
devices = {}

@app.route('/api/command/poll', methods=['GET'])
def poll_command():
    device_id = request.args.get('device_id')
    
    # 返回医院列表指令
    command = {
        "type": "hospital_list",
        "data": {
            "hospitals": [
                "北京协和医院",
                "上海九院",
                "广州南方医院"
            ]
        }
    }
    
    return jsonify(command)

@app.route('/api/command/result', methods=['POST'])
def receive_result():
    result = request.json
    
    # 保存抓取结果
    print(f"收到结果：{result['hospital_name']}")
    print(f"数据：{json.dumps(result['data'], ensure_ascii=False)}")
    
    return jsonify({"code": 200, "message": "success"})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080)
```

### Node.js 服务端示例

```javascript
const express = require('express');
const app = express();

app.use(express.json());

app.get('/api/command/poll', (req, res) => {
    const deviceId = req.query.device_id;
    
    res.json({
        type: 'hospital_list',
        data: {
            hospitals: ['北京协和医院', '上海九院', '广州南方医院']
        }
    });
});

app.post('/api/command/result', (req, res) => {
    const result = req.body;
    console.log('收到结果:', result.hospital_name);
    console.log('数据:', JSON.stringify(result.data, null, 2));
    
    res.json({ code: 200, message: 'success' });
});

app.listen(8080, () => {
    console.log('Server running on port 8080');
});
```

---

## 工作流程

```
1. 手机端启动轮询
   ↓
2. 服务端返回医院列表
   ↓
3. 手机端添加到任务队列
   ↓
4. 服务端发送"开始抓取"指令
   ↓
5. 手机端执行第一个任务:
   - 打开抖音
   - 搜索框输入医院名称
   - 点击搜索
   - 抓取医院信息
   - 抓取团单数据
   ↓
6. 上报抓取结果
   ↓
7. 继续下一个任务
```

---

## 错误处理

### 错误码

| code | 说明 |
|------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 404 | 设备未找到 |
| 500 | 服务器错误 |

### 错误响应

```json
{
  "code": 400,
  "message": "Invalid device_id"
}
```

---

## 安全建议

1. **内网使用** - 建议在局域网内使用，不暴露到公网
2. **设备认证** - 可添加设备 token 认证
3. **HTTPS** - 生产环境使用 HTTPS
4. **频率限制** - 限制轮询频率，防止滥用

---

*最后更新：2026-03-10*
