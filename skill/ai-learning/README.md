# AI/LLM 学习助手 Skill

## 简介

这是一个帮助系统学习AI/LLM技术的Claude Code Skill，支持：
- 系统化的学习计划（6个阶段，50+知识点）
- 细粒度的进度管理
- 对话式答疑解惑
- 实践项目指导

## 安装位置

```
~/.claude/skills/ai-learning/
├── SKILL.md           # 主指令文件
├── scripts/
│   └── progress.py    # 进度管理脚本
├── data/
│   └── progress.json  # 进度数据
└── README.md          # 本文件
```

## 使用方法

### 启动学习
```
/ai-learning
```

### 查看进度
```
/ai-learning progress
```

### 继续学习当前知识点
```
/ai-learning continue
```

### 标记知识点完成
```
/ai-learning complete 1.2
```

### 开始指定知识点
```
/ai-learning start 2.1
```

### 查看阶段详情
```
/ai-learning stage 1
```

### 提问
```
/ai-learning help 什么是RAG？
```

### 重置进度
```
/ai-learning reset
```

## 学习计划

| 阶段 | 名称 | 时长 | 知识点数 |
|------|------|------|----------|
| 1 | Prompt Engineering | 2周 | 6 |
| 2 | RAG实战 | 1个月 | 8 |
| 3 | Agent开发 | 1.5个月 | 10 |
| 4 | 模型微调 | 1个月 | 9 |
| 5 | 综合项目 | 2个月 | 10 |
| 6 | 面试冲刺 | 2个月 | 9 |

## 进度数据结构

进度数据存储在 `data/progress.json` 中：

```json
{
  "current_stage": "1",
  "current_point": "1.1",
  "stages": {
    "1": {
      "name": "Prompt Engineering",
      "points": {
        "1.1": {
          "name": "Prompt设计原则",
          "status": "pending",
          ...
        }
      }
    }
  }
}
```

## 状态说明

- `pending`: 待学习
- `in_progress`: 学习中
- `completed`: 已完成

## 注意事项

1. 首次使用会从第一个知识点开始
2. 每次学习完成后记得标记完成
3. 可以随时提问，不影响进度
4. 进度数据会自动保存

## 更新学习计划

如需修改学习计划，直接编辑 `data/progress.json` 文件。
