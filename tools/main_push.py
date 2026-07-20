from pathlib import Path
import sys

from adb_push_file import push_file


# 直接运行本脚本时，Python 只会自动搜索 tools 目录；这里显式加入制谱器目录。
PROJECT_DIR = Path(__file__).resolve().parent.parent
CHART_TOOL_DIR = PROJECT_DIR / "制谱器"
sys.path.insert(0, str(CHART_TOOL_DIR))

from auto_convert_logic import convert_logic_chart, convert_logic_charts, chart_for_song

# 单个谱面
# output = convert_logic_chart("0226", "master", force=False)
# 批量谱面
sources = [

]

for i in range(1,761):
    sources.append(chart_for_song(f"{i}", "easy"))
    sources.append(chart_for_song(f"{i}", "normal"))
    sources.append(chart_for_song(f"{i}", "hard"))
    sources.append(chart_for_song(f"{i}", "master"))
    sources.append(chart_for_song(f"{i}", "append"))



written, unchanged = convert_logic_charts(sources, force=False)

# print(written)
print(f"已转换: {len(written)}")




# push_list = [*written, *unchanged]

# D:\pjsk\pjsk\android_native\tools\sus_json
# 所有后缀为json的文件
push_list = [
    str(p) for p in Path("D:/pjsk/pjsk/android_native/tools/sus_json").glob("*.json")
]



phone_path = "/storage/emulated/0/Android/data/com.pjsk.autoplayer/files/logic_json"


for file_path in push_list:
    remote_path = push_file(
        file_path,
        phone_path,
        serial=None,      # 有线连接可不填
        root=False,
        verify=True,
    )
    print(f"已推送到: {remote_path}")


