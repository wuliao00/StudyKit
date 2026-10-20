#!/usr/bin/env bash
#
# 复制的 UI 文案不许有第二个家（v3.0-C 收口时自己欠下的那格体检，现在才补上）。
#
# 管的是什么：用户看得见的中文 UI 文案。每条整串字面量只许住在两个文件里 ——
#   定义体所在的 QuestionQueuePlanner.kt，和逐字钉住它的那条断言所在的 QuestionQueuePlannerTest.kt。
# 为什么这活轮不到测试干：render 测试比的是「屏上文本 vs 同一个函数的返回值」，两边都取自同一个函数，
#   抄一份字节相同的第二份进去它照样绿。这一类错误全仓没有任何测试看得见，只有脚本看得见。
#
# 比的是「文件路径集合」，不是出现次数：同一个允许文件里再写一遍第二断言是可接受的（弱，但无害）；
# 出现在第三个文件才是第二个家。别把「出现几次」写回这个脚本或它的注释 —— 抄来的计数本身就是一种缺陷。
#
# 用法：仓库任意位置都能跑（脚本自己 cd 到仓根）。本地 Git Bash：
#   bash .github/check-copied-strings.sh
# 不依赖任何 CI 环境变量。退出码：0 = 每一项的文件集合都与白名单相符；1 = 任何一项多出或缺少；2 = 脚本自己跑不起来。
#
# ⚠️ 这道闸门的弱点，如实写在这儿（不许对外声称「从此不会再有第二份」）：
#   ① 白名单文件内部再抄一遍：看不见 —— 设计如此，白名单是文件集合不是位置清单。
#   ② 抄的时候改了哪怕一个标点：看不见 —— 那属于「漂移」，归现有的逐字断言管（改生产代码或改断言都会红），
#      不是这里的活；两道闸门口径不同，别把它们混成一道。
#   ③ 运行时拼字符串造出同样的句子（split/join/模板/拼接别的函数返回值）：看不见 —— 这里是按字节段子串匹配的。
#   ④ 只扫 app/src：本脚本自己就写着这些字面量，扫描范围一旦扩到 .github/ 或 docs/ 或仓根，
#      闸门会把这个脚本和它的简报当成第二个家 —— 范围是刻意收窄的，不是漏了。
#
set -u

cd "$(dirname "${BASH_SOURCE[0]}")/.." || { echo "跑不到仓根"; exit 2; }
export LC_ALL=C            # 字面量按字节匹配，别让 locale 去解释中文标点
readonly SCAN_ROOT=app/src

FAILED=false

# check_copied_string <这一项的名字（含它的家归谁）> <整串字面量> <允许出现的文件……>
# 多出的文件和缺少的文件都算失败：少了 = 有人把定义体改了名或把那条断言删了，白名单跟着一起烂掉。
check_copied_string() {
  local name="$1" literal="$2" f
  local -a allowed=( "${@:3}" )
  [ "${#allowed[@]}" -gt 0 ] || { echo "::error::INTERNAL：「${name}」这一项没写白名单"; FAILED=true; return; }

  local found extra missing extra_line missing_line
  found="$(grep -rl -F -- "${literal}" "${SCAN_ROOT}" || true)"

  extra=""
  while IFS= read -r f; do
    [ -z "${f}" ] && continue
    printf '%s\n' "${allowed[@]}" | grep -Fxq -- "${f}" || extra="${extra}${f}"$'\n'
  done <<< "${found}"

  missing=""
  for f in "${allowed[@]}"; do
    printf '%s\n' "${found}" | grep -Fxq -- "${f}" || missing="${missing}${f}"$'\n'
  done

  extra_line="$(printf '%s' "${extra}" | tr '\n' ' ')"
  missing_line="$(printf '%s' "${missing}" | tr '\n' ' ')"

  if [ -n "${extra}" ]; then
    FAILED=true
    echo "::error::${name}：这句文案在它的家之外被发现了第二处 → ${extra_line}"
    echo "   它的家只有下面这些文件（白名单是文件集合，不是次数）："
    printf '   - %s\n' "${allowed[@]}"
    echo "   抄成第二份就是给这句话开第二个家：以后改一处忘改另一处，全仓没有任何测试看得见。"
    echo "   处置：删掉新加的那份拷贝，改成引用这个函数本身。"
  fi

  if [ -n "${missing}" ]; then
    FAILED=true
    echo "::error::${name}：白名单里的文件现在不含这句字面量 → ${missing_line}"
    echo "   定义体或那条逐字断言被改了名/删了/搬走了。搬家可以，但得同步改本脚本的白名单，"
    echo "   别让这道闸门在没人注意的时候静默松掉。"
  fi

  if [ -z "${extra}" ] && [ -z "${missing}" ]; then
    echo "OK ${name}：文件集合与白名单相符 —— 目前住在："
    printf '%s\n' "${found}" | sed '/^$/d; s/^/   - /'
  fi
}

# ── 闸门表：整串字面量 → 允许出现的文件集合 ─────────────────────────────────
# 新增受管文案就加一次 check_copied_string 调用；只写「家」，别写出现几次。
#
# semanticInterleaveHint() 的整句在源码里是两个 Kotlin 字符串常量拼起来的（"…再遇一次 —— " + "挪不开…"），
# 中间夹着 `" +`，任何跨那一行的整串都不是文件里的连续字节，-F 匹配不到。所以这一句按它的两段字节连续体
# 各管一项：第三份拷贝不管是抄整句还是只抄半句，至少会撞上其中一项。

check_copied_string "confusableDrillTip() 的会话内提示整句" \
  "这两张卡容易混，隔开放是故意的。" \
  "app/src/main/java/com/studykit/ui/study/QuestionQueuePlanner.kt" \
  "app/src/test/java/com/studykit/ui/study/QuestionQueuePlannerTest.kt"

check_copied_string "semanticInterleaveHint() 设置行说明整句·前半段" \
  "把题面意思相近的题尽量隔开几道出；同一轮里已经出现的同一学科同考点题，会尽量隔三到五题再遇一次" \
  "app/src/main/java/com/studykit/ui/study/QuestionQueuePlanner.kt" \
  "app/src/test/java/com/studykit/ui/study/QuestionQueuePlannerTest.kt"

check_copied_string "semanticInterleaveHint() 设置行说明整句·后半段" \
  "挪不开就照原样出。判据是端侧模型算出的题干相似度，不联网、不装额外东西。关掉这一层就只按学科打散。" \
  "app/src/main/java/com/studykit/ui/study/QuestionQueuePlanner.kt" \
  "app/src/test/java/com/studykit/ui/study/QuestionQueuePlannerTest.kt"

if [ "${FAILED}" = true ]; then
  echo "::error::UI 文案的「家」被改动了（见上面逐条）。这道闸门只比文件路径集合；"
  echo "   它能红给「第三个文件抄了一份」和「家整个不见了」，看不见「白名单文件内部再抄一遍」、"
  echo "   「抄的时候改了标点」（那归逐字断言）和「运行时拼出来的同样句子」。"
  exit 1
fi

echo "✅ 全部相符。本脚本检查了这些项（每项比的是文件路径集合，不是出现次数）："
echo "   - confusableDrillTip() 的会话内提示整句"
echo "   - semanticInterleaveHint() 设置行说明整句·前半段"
echo "   - semanticInterleaveHint() 设置行说明整句·后半段"
echo "   扫描范围：${SCAN_ROOT}（生产 + 单测 + androidTest），刻意不含本脚本自身与 docs/。"
exit 0
