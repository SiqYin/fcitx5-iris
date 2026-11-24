# -*- coding: utf-8 -*-
"""为纯汉字的搜狗词表生成普通话拼音码列（luna_pinyin 风格：无声调、v 代 ü）。

上游 luna_pinyin.sogou.dict.yaml 是纯汉字词条（无拼音列），Rime 编译时
依赖所在 schema 的编码器给词条生成码。独立编译时用本脚本预生成码列，
使词条走正常 CreateEntry 路径（不依赖 schema 的 algebra）。
已有码的行原样保留；生僻字按考证补码，兼容字映射后生成码；
无法拼音化的行保留无码原样（与上游桌面编译一致，一条不删，由编码器跳过）。
"""
import sys

from pypinyin import lazy_pinyin, Style

MANUAL_CODES = {
    "㐂": "xi",  # U+3402 三七，同「喜」
    "㽓": "xi",  # U+3F53 三生，和制汉字
    "𣊭": "mu",  # U+232AD 暮之异体
}

CHAR_MAPPINGS = {
    "\uf979": "凉",  # U+F979 CJK COMPATIBILITY IDEOGRAPH-F979 -> 凉
}

path = sys.argv[1]
data = open(path, encoding="utf-8").read()

marker = "...\n"
idx = data.find(marker)
head = data[: idx + len(marker)]
body = data[idx + len(marker) :]

out_lines = []
kept_with_code = 0
kept_without_code = 0

for line in body.split("\n"):
    s = line.strip("\r").strip()
    if not s or s.startswith("#"):
        if line.strip("\r"):
            out_lines.append(line.strip("\r"))
        continue
    if "\t" in s:
        out_lines.append(line.strip("\r"))  # 已有码，原样保留
        kept_with_code += 1
        continue
    word = s
    if word in MANUAL_CODES:
        out_lines.append(word + "\t" + MANUAL_CODES[word])
        kept_with_code += 1
        continue
    mapped_word = word
    for src, dst in CHAR_MAPPINGS.items():
        if src in mapped_word:
            mapped_word = mapped_word.replace(src, dst)
    try:
        py = lazy_pinyin(mapped_word, style=Style.NORMAL, v_to_u=False)
        code = " ".join(py)
    except Exception:
        code = ""
    if code and all(c.isascii() and (c.isalpha() or c == " ") for c in code) and code.strip():
        out_lines.append(word + "\t" + code)
        kept_with_code += 1
    else:
        out_lines.append(line.strip("\r"))  # 无法拼音化的行保留无码原样
        kept_without_code += 1

open(path, "w", encoding="utf-8", newline="\n").write(head + "\n" + "\n".join(out_lines) + "\n")
print(
    "generated pinyin codes: with_code=%d without_code=%d total=%d (0 dropped)"
    % (kept_with_code, kept_without_code, kept_with_code + kept_without_code)
)
