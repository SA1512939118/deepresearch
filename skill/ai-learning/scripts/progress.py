#!/usr/bin/env python3
"""
AI学习进度管理脚本
功能：加载、保存、更新、显示学习进度
"""
import json
import sys
from pathlib import Path
from datetime import datetime
from typing import Optional, Dict, Any, List, Tuple

SKILL_DIR = Path(__file__).parent.parent
DATA_FILE = SKILL_DIR / "data" / "progress.json"


def load_data() -> Dict[str, Any]:
    """加载进度数据"""
    if not DATA_FILE.exists():
        print(f"错误: 进度文件不存在 {DATA_FILE}")
        sys.exit(1)

    with open(DATA_FILE, 'r', encoding='utf-8') as f:
        return json.load(f)


def save_data(data: Dict[str, Any]) -> None:
    """保存进度数据"""
    data["last_update"] = datetime.now().isoformat()
    if data.get("created_at") is None:
        data["created_at"] = datetime.now().isoformat()

    DATA_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(DATA_FILE, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def get_current_point(data: Dict[str, Any]) -> Tuple[Optional[str], Optional[str], Optional[Dict]]:
    """获取当前待学习的知识点"""
    current_stage = data.get("current_stage")
    current_point = data.get("current_point")

    if current_stage and current_point:
        stage = data["stages"].get(current_stage)
        if stage:
            point = stage["points"].get(current_point)
            if point and point["status"] in ["pending", "in_progress"]:
                return current_stage, current_point, point

    # 如果当前知识点已完成，找下一个
    for stage_id, stage in data["stages"].items():
        for point_id, point in stage["points"].items():
            if point["status"] in ["pending", "in_progress"]:
                return stage_id, point_id, point

    return None, None, None


def get_next_point(data: Dict[str, Any], stage_id: str, point_id: str) -> Tuple[Optional[str], Optional[str], Optional[Dict]]:
    """获取下一个知识点"""
    stage = data["stages"].get(stage_id)
    if not stage:
        return None, None, None

    points = list(stage["points"].items())
    current_idx = next((i for i, (pid, _) in enumerate(points) if pid == point_id), -1)

    if current_idx == -1:
        return None, None, None

    # 查找当前阶段的下一个知识点
    for i in range(current_idx + 1, len(points)):
        pid, point = points[i]
        if point["status"] == "pending":
            return stage_id, pid, point

    # 当前阶段已全部完成，查找下一阶段
    stage_ids = list(data["stages"].keys())
    current_stage_idx = stage_ids.index(stage_id)

    for i in range(current_stage_idx + 1, len(stage_ids)):
        next_stage_id = stage_ids[i]
        next_stage = data["stages"][next_stage_id]
        for pid, point in next_stage["points"].items():
            if point["status"] == "pending":
                return next_stage_id, pid, point

    return None, None, None


def show_progress(data: Optional[Dict[str, Any]] = None) -> None:
    """显示整体进度"""
    if data is None:
        data = load_data()

    print("\n" + "=" * 60)
    print("📊 AI/LLM 学习进度")
    print("=" * 60)

    total_points = 0
    completed_points = 0

    for stage_id, stage in data["stages"].items():
        stage_total = len(stage["points"])
        stage_completed = sum(1 for p in stage["points"].values() if p["status"] == "completed")
        total_points += stage_total
        completed_points += stage_completed

        pct = int(stage_completed / stage_total * 100) if stage_total > 0 else 0

        if pct == 100:
            icon = "✅"
        elif pct > 0:
            icon = "🔄"
        else:
            icon = "⏳"

        print(f"\n{icon} 阶段 {stage_id}: {stage['name']} ({stage['duration']})")
        print(f"   进度: {stage_completed}/{stage_total} ({pct}%)")

        for point_id, point in stage["points"].items():
            if point["status"] == "completed":
                status = "✓"
            elif point["status"] == "in_progress":
                status = "▶"
            else:
                status = "○"

            point_type = point.get("type", "")
            type_icon = "📁" if point_type == "project" else "📋" if point_type == "design" else "💻" if point_type == "coding" else "🧪" if point_type == "testing" else "🚀" if point_type == "deployment" else ""

            print(f"     {status} {point_id}: {point['name']} {type_icon}")

    print("\n" + "-" * 60)
    total_pct = int(completed_points / total_points * 100) if total_points > 0 else 0
    print(f"📈 总体进度: {completed_points}/{total_points} ({total_pct}%)")
    print("=" * 60 + "\n")


def show_current(data: Optional[Dict[str, Any]] = None) -> None:
    """显示当前学习内容"""
    if data is None:
        data = load_data()

    stage_id, point_id, point = get_current_point(data)

    if not point:
        print("\n🎉 恭喜！所有学习内容已完成！")
        return

    stage = data["stages"][stage_id]

    print("\n" + "=" * 60)
    print(f"📚 当前学习内容")
    print("=" * 60)
    print(f"\n阶段 {stage_id}: {stage['name']} ({stage['duration']})")
    print(f"知识点 {point_id}: {point['name']}")
    print("-" * 60)

    if point.get("description"):
        print(f"\n📝 描述:\n   {point['description']}")

    if point.get("key_concepts"):
        print(f"\n🔑 核心概念:")
        for concept in point["key_concepts"]:
            print(f"   • {concept}")

    if point.get("resources"):
        print(f"\n📖 学习资源:")
        for resource in point["resources"]:
            print(f"   • {resource}")

    if point.get("practice"):
        print(f"\n💪 实践任务:\n   {point['practice']}")

    if point.get("type") == "project" and point.get("requirements"):
        print(f"\n📋 项目要求:")
        for req in point["requirements"]:
            print(f"   • {req}")

    if point.get("tech_stack"):
        print(f"\n🛠️ 技术栈: {', '.join(point['tech_stack'])}")

    if point.get("hardware"):
        print(f"\n💻 硬件要求: {point['hardware']}")

    print("\n" + "=" * 60)
    print(f"💡 完成后运行: /ai-learning complete {stage_id}.{point_id}")
    print(f"💡 遇到问题运行: /ai-learning help [问题内容]")
    print("=" * 60 + "\n")


def mark_complete(point_ref: str) -> None:
    """标记知识点完成"""
    data = load_data()

    parts = point_ref.split(".")
    if len(parts) != 2:
        print("❌ 格式错误，应为: 阶段ID.知识点ID (如 1.2)")
        return

    stage_id, point_id = parts

    if stage_id not in data["stages"]:
        print(f"❌ 阶段 {stage_id} 不存在")
        return

    stage = data["stages"][stage_id]
    if point_id not in stage["points"]:
        print(f"❌ 知识点 {point_id} 不存在")
        return

    point = stage["points"][point_id]
    point["status"] = "completed"
    point["completed_date"] = datetime.now().isoformat()

    # 更新当前进度
    next_stage, next_point, _ = get_next_point(data, stage_id, point_id)
    if next_stage and next_point:
        data["current_stage"] = next_stage
        data["current_point"] = next_point
    else:
        data["current_stage"] = None
        data["current_point"] = None

    save_data(data)

    print(f"\n✅ 已完成: {stage['name']} - {point['name']}")

    # 显示进度
    show_progress(data)

    # 显示下一个知识点
    if next_stage and next_point:
        print(f"👉 下一个知识点: {data['stages'][next_stage]['name']} - {data['stages'][next_stage]['points'][next_point]['name']}")
        print(f"   运行 /ai-learning continue 开始学习")
    else:
        print("\n🎉 恭喜！所有学习内容已完成！")


def mark_in_progress(point_ref: str) -> None:
    """标记知识点为进行中"""
    data = load_data()

    parts = point_ref.split(".")
    if len(parts) != 2:
        print("❌ 格式错误，应为: 阶段ID.知识点ID (如 1.2)")
        return

    stage_id, point_id = parts

    if stage_id not in data["stages"]:
        print(f"❌ 阶段 {stage_id} 不存在")
        return

    stage = data["stages"][stage_id]
    if point_id not in stage["points"]:
        print(f"❌ 知识点 {point_id} 不存在")
        return

    point = stage["points"][point_id]
    point["status"] = "in_progress"
    point["started_date"] = datetime.now().isoformat()

    data["current_stage"] = stage_id
    data["current_point"] = point_id

    save_data(data)

    print(f"\n▶️ 开始学习: {stage['name']} - {point['name']}")
    show_current(data)


def show_stage(stage_id: str) -> None:
    """显示指定阶段的详情"""
    data = load_data()

    if stage_id not in data["stages"]:
        print(f"❌ 阶段 {stage_id} 不存在")
        return

    stage = data["stages"][stage_id]

    print("\n" + "=" * 60)
    print(f"📚 阶段 {stage_id}: {stage['name']}")
    print("=" * 60)
    print(f"⏱️ 预计时长: {stage['duration']}")
    print(f"📝 描述: {stage['description']}")

    stage_completed = sum(1 for p in stage["points"].values() if p["status"] == "completed")
    stage_total = len(stage["points"])
    pct = int(stage_completed / stage_total * 100) if stage_total > 0 else 0
    print(f"📊 进度: {stage_completed}/{stage_total} ({pct}%)")

    print("\n" + "-" * 60)
    print("知识点列表:")
    print("-" * 60)

    for point_id, point in stage["points"].items():
        if point["status"] == "completed":
            status = "✅"
        elif point["status"] == "in_progress":
            status = "▶️"
        else:
            status = "⏳"

        point_type = point.get("type", "")
        if point_type == "project":
            type_str = "[项目]"
        elif point_type == "design":
            type_str = "[设计]"
        elif point_type == "coding":
            type_str = "[编码]"
        elif point_type == "testing":
            type_str = "[测试]"
        elif point_type == "deployment":
            type_str = "[部署]"
        else:
            type_str = ""

        print(f"\n{status} {point_id}: {point['name']} {type_str}")
        print(f"   {point['description']}")

        if point.get("key_concepts"):
            print(f"   核心概念: {', '.join(point['key_concepts'])}")

    print("\n" + "=" * 60 + "\n")


def reset_progress() -> None:
    """重置所有进度"""
    data = load_data()

    for stage in data["stages"].values():
        for point in stage["points"].values():
            point["status"] = "pending"
            point.pop("completed_date", None)
            point.pop("started_date", None)

    data["current_stage"] = "1"
    data["current_point"] = "1.1"

    save_data(data)
    print("\n🔄 已重置所有进度")
    show_progress(data)


def get_progress_for_claude() -> str:
    """获取进度信息供Claude使用"""
    data = load_data()
    stage_id, point_id, point = get_current_point(data)

    if not point:
        return "所有学习内容已完成！"

    stage = data["stages"][stage_id]

    result = f"""当前学习进度:
阶段: {stage_id} - {stage['name']}
知识点: {point_id} - {point['name']}
状态: {point['status']}

描述: {point.get('description', '无')}

核心概念: {', '.join(point.get('key_concepts', []))}

学习资源:
{chr(10).join('  - ' + r for r in point.get('resources', []))}

实践任务: {point.get('practice', '无')}
"""
    return result


def main():
    """主函数"""
    if len(sys.argv) < 2:
        show_current()
        return

    cmd = sys.argv[1]

    if cmd == "progress":
        show_progress()
    elif cmd == "current":
        show_current()
    elif cmd == "continue":
        data = load_data()
        stage_id, point_id, point = get_current_point(data)
        if point:
            mark_in_progress(f"{stage_id}.{point_id}")
        else:
            print("\n🎉 所有学习内容已完成！")
    elif cmd == "complete":
        if len(sys.argv) < 3:
            print("❌ 请指定知识点，如: complete 1.2")
            return
        mark_complete(sys.argv[2])
    elif cmd == "start":
        if len(sys.argv) < 3:
            print("❌ 请指定知识点，如: start 1.2")
            return
        mark_in_progress(sys.argv[2])
    elif cmd == "stage":
        if len(sys.argv) < 3:
            print("❌ 请指定阶段ID，如: stage 1")
            return
        show_stage(sys.argv[2])
    elif cmd == "reset":
        reset_progress()
    elif cmd == "info":
        print(get_progress_for_claude())
    else:
        print(f"❌ 未知命令: {cmd}")
        print("\n可用命令:")
        print("  progress     - 显示整体进度")
        print("  current      - 显示当前学习内容")
        print("  continue     - 继续当前知识点学习")
        print("  complete X.Y - 标记知识点完成")
        print("  start X.Y    - 开始指定知识点")
        print("  stage X      - 显示指定阶段详情")
        print("  reset        - 重置所有进度")
        print("  info         - 获取进度信息(JSON格式)")


if __name__ == "__main__":
    main()
