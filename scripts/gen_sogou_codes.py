# -*- coding: utf-8 -*-
"""为纯汉字的搜狗词表生成普通话拼音码列（luna_pinyin 风格：无声调、v 代 ü）。

上游 luna_pinyin.sogou.dict.yaml 是纯汉字词条（无拼音列），Rime 编译时
依赖所在 schema 的编码器给词条生成码。独立编译时用本脚本预生成码列，
使词条走正常 CreateEntry 路径（不依赖 schema 的 algebra）。
已有码的行原样保留；无法生成有效拼音的行（生僻单字等）直接丢弃。
"""
import sys

from pypinyin import lazy_pinyin, Style

path = sys.argv[1]
data = open(path, encoding="utf-8").read()

marker = "...\n"
idx = data.find(marker)
head = data[: idx + len(marker)]
body = data[idx + len(marker) :]

out_lines = []
kept = 0
dropped = 0
for line in body.split("\n"):
    s = line.strip("\r").strip()
    if not s or s.startswith("#"):
        if line.strip("\r"):
            out_lines.append(line.strip("\r"))
        continue
    if "\t" in s:
        out_lines.append(line.strip("\r"))  # 已有码，原样保留
        kept += 1
        continue
    word = s
    try:
        py = lazy_pinyin(word, style=Style.NORMAL, v_to_u=False)
    except Exception:
        dropped += 1
        continue
    code = " ".join(py)
    # 码列必须全部为拉丁字母与空格，否则丢弃（生僻字/无法拼音化的行）
    if code and all(c.isascii() and (c.isalpha() or c == " ") for c in code) and code.strip():
        out_lines.append(word + "\t" + code)
        kept += 1
    else:
        dropped += 1

open(path, "w", encoding="utf-8", newline="\n").write(head + "\n" + "\n".join(out_lines) + "\n")
print("generated pinyin codes: kept=%d dropped=%d" % (kept, dropped))
