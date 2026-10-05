//! v2.9/R2 口径一致 fixture 生成器（Rust 侧 = 期望值的唯一来源）。
//!
//! 干什么：把 `core.rs` 里那组**纯 Double（无梯度）前向单式函数**对一批固定输入逐条算出结果，
//! 落成 `fsrsjni/fixtures/forward_parity.json`。Kotlin 侧 `FsrsModelParityTest` 读同一份 JSON
//! 断言 `FsrsModel` 的每个纯函数逐值相符（容差 1e-6）——这就是"Rust v6 前向 ≡ Kotlin v6 前向"
//! 的硬闸。**期望数字只能由本程序产出**：若改成用 Kotlin 反推期望，闸门就成了自我循环论证。
//!
//! 为什么不用 Robolectric 直接调 `nativeEvaluate` 对拍：`libfsrsjni.so` 是 arm64/armv7 的
//! JNI 库，JVM 单测（CI 唯一执行器）既加载不了 arm .so，也没有 x86 host .so 可加载，
//! 所以口径一致改由这份**提交进仓的 fixture** 承担；JNI 链路本身在真机 T13 验证。
//!
//! 重新生成（模型公式一旦改动就必须重跑，并把新 JSON 同步复制到 app 的测试资源）：
//!     cd fsrsjni
//!     cargo run --bin gen_fixture
//!     copy fixtures\forward_parity.json ..\app\src\test\resources\fsrs_forward_parity.json
//!
//! 确定性：输入是一张手写的用例表（无 RNG、无时间、无环境依赖），同样的代码必然产出同样的 JSON，
//! 所以 `git diff` 只会反映模型语义的真实变化，不会被浮点抖动污染。

use std::path::Path;

use fsrsjni::core::{
    d0_raw_v, d0_v, next_d_v, next_s_lapse_v, next_s_ok_v, next_s_short_v, recall_v, s0_idx,
    DEFAULT_PARAMS, MAX, MIN, MIN_STABILITY_FLOOR,
};
use fsrsjni::dual::NP;

/// 用 `(下标, 值)` 覆盖项拼出一组参数，基线是 `DEFAULT_PARAMS`。
/// 覆盖值一律取自 py-fsrs 6.3.2 的 `MIN`/`MAX` 边界或其内的合法取值，保证用例落在真实可达的参数空间里。
fn variant(overrides: &[(usize, f64)]) -> [f64; NP] {
    let mut w = DEFAULT_PARAMS;
    for &(i, v) in overrides {
        assert!(
            v >= MIN[i] - 1e-12 && v <= MAX[i] + 1e-12,
            "用例参数 w[{i}]={v} 越界 [{}, {}]，夹具会测到模型外的区域",
            MIN[i],
            MAX[i]
        );
        w[i] = v;
    }
    w
}

/// 参数变体表。重点是 w[20]（decay）——v6 里它是可学习项，DECAY/FACTOR 都随它变，
/// 所以每个 decay 档位都要有样本：Kotlin/Rust 任一侧把 decay 写死成 −0.5（v5 口径）都会被这份夹具抓到。
fn variants() -> Vec<[f64; NP]> {
    vec![
        // V0：上线默认（w20 = 0.1542）
        variant(&[]),
        // V1：decay 下界
        variant(&[(20, MIN[20])]),
        // V2：decay 中段
        variant(&[(20, 0.5)]),
        // V3：decay 上界（曲线更平，FACTOR 随之一变）
        variant(&[(20, MAX[20])]),
        // V4：整体扰动（init 四档、难度、成功/失败支、短期段同时挪位置）
        variant(&[
            (0, 0.4),
            (1, 1.0),
            (2, 3.0),
            (3, 5.0),
            (4, 7.0),
            (5, 1.5),
            (6, 1.0),
            (7, 0.5),
            (8, 2.5),
            (9, 0.3),
            (10, 1.5),
            (11, 2.0),
            (12, 0.1),
            (13, 0.5),
            (14, 2.5),
            (15, 0.8),
            (16, 3.0),
            (17, 1.0),
            (18, 0.5),
            (19, 0.2),
            (20, 0.3),
        ]),
        // V5：全参数贴边界的角点（夹取分支最容易在这里分叉：S0=100、D0 被夹成 1、短期帽 s/e^4）
        variant(&[
            (0, MAX[0]),
            (1, MAX[1]),
            (2, MAX[2]),
            (3, MAX[3]),
            (4, MIN[4]),
            (5, MAX[5]),
            (6, MAX[6]),
            (7, MAX[7]),
            (8, MIN[8]),
            (9, MAX[9]),
            (10, MAX[10]),
            (11, MAX[11]),
            (12, MAX[12]),
            (13, MAX[13]),
            (14, MAX[14]),
            (15, MIN[15]),
            (16, MAX[16]),
            (17, MAX[17]),
            (18, MAX[18]),
            (19, MAX[19]),
            (20, MIN[20]),
        ]),
    ]
}

/// 用例 = (参数变体, S, D, t, rating)。覆盖：
/// 四档评分、elapsed<1 的短期支、t==1 的长期/短期分界、长间隔与近 lapse、S 上下限、
/// 难度两端（1 与 10）、以及链式取值（上一条算出的 S' 接着当下一条的 S）。
fn cases() -> Vec<(usize, f64, f64, f64, u32)> {
    // 黄金锚点的输入（docs/evidence/fsrs6-golden.json：S=2.3065, D=2.118103970459016, t=2）
    const ANCHOR_S: f64 = 2.3065;
    const ANCHOR_D: f64 = 2.118103970459016;
    let mut v: Vec<(usize, f64, f64, f64, u32)> = Vec::new();

    // —— V0 默认参数：四档评分全走一遍 ——
    for g in 1..=4u32 {
        v.push((0, ANCHOR_S, ANCHOR_D, 2.0, g));
    }
    // —— V0：intra-day 短期支（elapsed<1），四档各一条 ——
    v.extend([
        (0, 0.5, 5.0, 0.2, 1),
        (0, 0.5, 5.0, 0.2, 2),
        (0, 0.5, 5.0, 0.35, 3),
        (0, 0.5, 5.0, 0.9, 4),
    ]);
    // —— V0：长期/短期分界（t==1）与难度两端 ——
    v.extend([(0, 1.0, 1.0, 1.0, 3), (0, 1.0, 10.0, 1.0, 1), (0, 2.0, 9.9, 1.5, 1)]);
    // —— V0：长间隔 / 近 lapse / 稳定度上下限 ——
    v.extend([
        (0, 50.0, 8.0, 100.0, 1),
        (0, 50.0, 8.0, 100.0, 3),
        (0, 365.0, 3.0, 400.0, 2),
        (0, 36500.0, 5.0, 1000.0, 4),
        (0, MIN_STABILITY_FLOOR, 10.0, 0.5, 1),
    ]);
    // —— V0：链式（recallStability 的输出当输入，钉住迭代口径）——
    v.push((0, 10.964332335820698, ANCHOR_D, 2.0, 3));

    // —— V1/V2/V3：只挪 w[20]（decay 下界/中值/上界），同输入扫一遍四档 ——
    for (vi, s, t) in [(1usize, 1.0f64, 2.0f64), (2, 10.0, 5.0), (3, 100.0, 30.0)] {
        for g in 1..=4u32 {
            v.push((vi, s, ANCHOR_D, t, g));
        }
    }

    // —— V4：整体扰动 ——
    v.extend([
        (4, ANCHOR_S, ANCHOR_D, 2.0, 1),
        (4, ANCHOR_S, ANCHOR_D, 2.0, 2),
        (4, ANCHOR_S, ANCHOR_D, 2.0, 3),
        (4, 3.0, 6.0, 20.0, 4),
        (4, 0.4, 2.0, 0.1, 2),
        (4, 12.0, 9.0, 12.0, 1),
    ]);

    // —— V5：贴边界角点（夹取分支 + 极端 FACTOR）——
    v.extend([
        (5, ANCHOR_S, ANCHOR_D, 2.0, 1),
        (5, ANCHOR_S, ANCHOR_D, 2.0, 3),
        (5, 100.0, 1.0, 30.0, 4),
        (5, 100.0, 10.0, 30.0, 2),
        (5, 0.001, 5.0, 0.05, 4),
    ]);
    v
}

/// 夹具必须全是有限值：JSON 存不下 NaN/Inf，断言一旦落到非有限分支就等于闸门失声。
fn finite(x: f64, tag: &str, ci: usize) -> f64 {
    assert!(x.is_finite(), "用例 {ci} 的 {tag} = {x} 非有限，得换用例");
    x
}

/// 期望值按固定顺序产出，写 JSON 与打印核对共用，避免两处顺序漂移。
fn expect_for(w: &[f64; NP], s: f64, d: f64, t: f64, g: u32, ci: usize) -> [(&'static str, f64); 8] {
    let r = finite(recall_v(w, s, t), "recall", ci);
    [
        ("recall", r),
        ("nextS_ok", finite(next_s_ok_v(w, s, d, r, g), "nextS_ok", ci)),
        ("nextS_lapse", finite(next_s_lapse_v(w, s, d, r), "nextS_lapse", ci)),
        ("nextS_short", finite(next_s_short_v(w, s, g as f64), "nextS_short", ci)),
        ("nextD", finite(next_d_v(w, d, g as f64), "nextD", ci)),
        ("d0raw", finite(d0_raw_v(w, g as f64), "d0raw", ci)),
        ("d0", finite(d0_v(w, g as f64), "d0", ci)),
        // s0 与 Kotlin `initStability` 同口径：取 w[G-1] 再抬到 MIN_STABILITY_FLOOR 之上
        ("s0", finite(w[s0_idx(g)].max(MIN_STABILITY_FLOOR), "s0", ci)),
    ]
}

fn main() {
    let vs = variants();
    let all = cases();
    let mut out = String::new();
    out.push_str("[\n");

    for (ci, &(vi, s, d, t, g)) in all.iter().enumerate() {
        let w = &vs[vi];
        let e = expect_for(w, s, d, t, g, ci);

        out.push_str("  {\n");
        out.push_str(&format!("    \"note\": \"V{vi} · S={s} D={d} t={t} rating={g}\",\n"));
        out.push_str("    \"w\": [");
        for (i, &x) in w.iter().enumerate() {
            if i > 0 {
                out.push_str(", ");
            }
            out.push_str(&format!("{x}"));
        }
        out.push_str("],\n");
        out.push_str(&format!("    \"s\": {s},\n    \"d\": {d},\n    \"t\": {t},\n"));
        out.push_str(&format!("    \"rating\": {g},\n    \"expect\": {{\n"));
        for (j, (k, x)) in e.iter().enumerate() {
            out.push_str(&format!("      \"{k}\": {x}"));
            out.push_str(if j + 1 < e.len() { ",\n" } else { "\n" });
        }
        out.push_str("    }\n");
        out.push_str(if ci + 1 == all.len() { "  }\n" } else { "  },\n" });
    }
    out.push_str("]\n");

    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures");
    std::fs::create_dir_all(&dir).expect("创建 fixtures 目录失败");
    let path = dir.join("forward_parity.json");
    std::fs::write(&path, out).expect("写 forward_parity.json 失败");

    println!("已写出 {} 条用例 → {}", all.len(), path.display());
    for (ci, &(vi, s, d, t, g)) in all.iter().take(4).enumerate() {
        let w = &vs[vi];
        let e = expect_for(w, s, d, t, g, ci);
        println!(
            "  case[{ci}] V{vi} S={s} D={d} t={t} g={g} → recall={} ok={} lapse={} short={} nextD={} d0={}",
            e[0].1, e[1].1, e[2].1, e[3].1, e[4].1, e[6].1
        );
    }
}
