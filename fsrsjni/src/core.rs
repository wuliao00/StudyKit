//! FSRS v6（21 参）训练核：状态方程 + BCE 损失 + 校准桶 + AdamW。
//! 公式与上线的 `FsrsModel.kt` 逐条对齐（单一事实源）；decay/factor 随 w[20] 可学习。
use crate::dual::{D, NP};

/// v6 默认参数（21 项）——逐值取自 py-fsrs 6.3.2 `DEFAULT_PARAMETERS`，等于 `FsrsModel.DEFAULT_PARAMS`。
pub const DEFAULT_PARAMS: [f64; NP] = [
    0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835,
    0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
];

/// 参数下界——逐值取自 py-fsrs 6.3.2 `LOWER_BOUNDS_PARAMETERS`（STABILITY_MIN=0.001）。
pub const MIN: [f64; NP] = [
    0.001, 0.001, 0.001, 0.001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0, 0.001, 0.001, 0.001, 0.001,
    0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
];

/// 参数上界——逐值取自 py-fsrs 6.3.2 `UPPER_BOUNDS_PARAMETERS`（INITIAL_STABILITY_MAX=100.0）。
pub const MAX: [f64; NP] = [
    100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8, 3.5, 5.0, 0.25, 0.9, 4.0, 1.0,
    6.0, 2.0, 2.0, 0.8, 0.8,
];

/// 稳定度下限，对齐 py-fsrs `STABILITY_MIN` 与 `FsrsModel.MIN_STABILITY_FLOOR`。
pub const MIN_STABILITY_FLOOR: f64 = 0.001;
/// 稳定度上限（≈100 年），对齐 `FsrsModel.MAX_STABILITY`。
pub const MAX_STABILITY: f64 = 36500.0;

/// 单条 review：rating(1..4)、经过天数、上次排期天数、状态(0=New 1=Learn 2=Review 3=Relearn)。
#[derive(Clone, Copy, Debug)]
pub struct Rev {
    pub rating: u32,
    pub elapsed: f64,
    pub sched: f64,
    pub state: u32,
}

/// 一张卡的完整复习序列（按时间升序，[0] 为首评）。
pub type Item = Vec<Rev>;

// ===================== 纯 Double 参考实现（forward_val 与测试用）=====================
// 与 FsrsModel.kt 逐条对齐，作为 dual 前向的"值"基准。
//
// 这些单条公式函数在 R1 只需要 crate 内可见，R2 起被 `src/bin/gen_fixture.rs` 直接调用来
// 产出 `fsrsjni/fixtures/forward_parity.json`——那份 fixture 是"Rust 前向 ≡ Kotlin FsrsModel"
// 口径一致硬闸的期望值来源，故整体升到 `pub`（仅经由 `pub mod core` 暴露，JNI 契约不变）。

/// `DECAY = −w[20]`（v6 可学习衰减指数），≡ `FsrsModel.decay`。
pub fn decay_v(w: &[f64; NP]) -> f64 {
    -w[20]
}
/// `FACTOR = 0.9^(1/DECAY) − 1`，≡ `FsrsModel.factor`。
pub fn factor_v(w: &[f64; NP]) -> f64 {
    0.9f64.powf(1.0 / decay_v(w)) - 1.0
}
/// 可提取性 `R = (1 + FACTOR·t/S)^DECAY`，≡ `FsrsModel.recall`（未做 1e-7 夹取，与 Kotlin 同口径）。
pub fn recall_v(w: &[f64; NP], s: f64, t: f64) -> f64 {
    (1.0 + factor_v(w) * t / s).powf(decay_v(w))
}
/// 首评稳定性取 `w[G-1]` 的下标（越界夹到 0..3），≡ `FsrsModel.initStability` 的索引口径。
pub fn s0_idx(g: u32) -> usize {
    ((g as i32 - 1).clamp(0, 3)) as usize
}
/// `D0(G) = w[4] − e^(w[5]·(G−1)) + 1`（未夹取，均值回归目标项），≡ `FsrsModel.initDifficultyRaw`。
pub fn d0_raw_v(w: &[f64; NP], g: f64) -> f64 {
    w[4] - (w[5] * (g - 1.0)).exp() + 1.0
}
/// 落库口径的 `D0`（夹 [1,10]），≡ `FsrsModel.initDifficulty`。
pub fn d0_v(w: &[f64; NP], g: f64) -> f64 {
    d0_raw_v(w, g).clamp(1.0, 10.0)
}
/// 下一次难度（均值回归到未夹取的 D0(Easy)），≡ `FsrsModel.nextDifficulty`。
pub fn next_d_v(w: &[f64; NP], d: f64, g: f64) -> f64 {
    let delta = -w[6] * (g - 3.0);
    let arg2 = d + (10.0 - d) * delta / 9.0;
    let arg1 = d0_raw_v(w, 4.0);
    (w[7] * arg1 + (1.0 - w[7]) * arg2).clamp(1.0, 10.0)
}
/// 成功支（Hard/Good/Easy）长期稳定性，≡ `FsrsModel.recallStability`。
pub fn next_s_ok_v(w: &[f64; NP], s: f64, d: f64, r: f64, g: u32) -> f64 {
    let hard = if g == 2 { w[15] } else { 1.0 };
    let easy = if g == 4 { w[16] } else { 1.0 };
    let inc = w[8].exp()
        * (11.0 - d)
        * s.powf(-w[9])
        * (((1.0 - r) * w[10]).exp() - 1.0)
        * hard
        * easy;
    (s * (inc + 1.0)).clamp(MIN_STABILITY_FLOOR, MAX_STABILITY)
}
/// lapse 支 `min(长期项, S/e^(w17·w18))`，≡ `FsrsModel.forgetStability`。
pub fn next_s_lapse_v(w: &[f64; NP], s: f64, d: f64, r: f64) -> f64 {
    let long = w[11] * d.powf(-w[12]) * ((s + 1.0).powf(w[13]) - 1.0) * ((1.0 - r) * w[14]).exp();
    let short_cap = s / (w[17] * w[18]).exp();
    long.min(short_cap).max(MIN_STABILITY_FLOOR)
}
/// intra-day 短期稳定性（`elapsed < 1`），≡ `FsrsModel.shortTermStability`。
pub fn next_s_short_v(w: &[f64; NP], s: f64, g: f64) -> f64 {
    let mut inc = (w[17] * (g - 3.0 + w[18])).exp() * s.powf(-w[19]);
    if g >= 2.0 {
        inc = inc.max(1.0);
    }
    (s * inc).max(MIN_STABILITY_FLOOR)
}

/// 纯 Double 单遍前向（无梯度）：只回传 (mean loss, preds)。用作 dual 值一致基准。
pub fn forward_val(items: &[Item], w: &[f64; NP]) -> (f64, Vec<(f64, f64)>) {
    let mut loss = 0.0f64;
    let mut n = 0usize;
    let mut preds: Vec<(f64, f64)> = Vec::new();
    for it in items {
        let (mut s, mut d) = (0.0f64, 0.0f64);
        for (idx, rv) in it.iter().enumerate() {
            if idx == 0 {
                s = w[s0_idx(rv.rating)];
                d = d0_v(w, rv.rating as f64);
                continue;
            }
            if rv.elapsed >= 1.0 {
                let r = recall_v(w, s, rv.elapsed).clamp(1e-7, 1.0 - 1e-7);
                let y = if rv.rating >= 2 { 1.0 } else { 0.0 };
                loss += -(y * r.ln() + (1.0 - y) * (1.0 - r).ln());
                n += 1;
                preds.push((r, y));
                d = next_d_v(w, d, rv.rating as f64);
                s = if rv.rating == 1 {
                    next_s_lapse_v(w, s, d, r)
                } else {
                    next_s_ok_v(w, s, d, r, rv.rating)
                };
            } else {
                s = next_s_short_v(w, s, rv.rating as f64);
            }
        }
    }
    if n == 0 {
        return (f64::NAN, preds);
    }
    (loss / n as f64, preds)
}

// ===================== dual（带 21 维梯度）实现 ======================

fn decay_d(w: &[D; NP]) -> D {
    w[20].neg()
}
/// factor = 0.9^(1/DECAY) - 1；底为常数、指数随 w[20] 变，梯度经指数侧回传到 idx20。
fn factor_d(w: &[D; NP]) -> D {
    let one = D::k(1.0);
    let recip = one.div(decay_d(w)); // 1/DECAY
    recip.scale(0.9f64.ln()).exp().shift(-1.0) // exp(recip·ln0.9) - 1
}
/// recall = (1 + FACTOR·t/s)^DECAY；底、指数皆带梯度（变底变指 powv）。
fn recall_d(w: &[D; NP], s: D, t: f64) -> D {
    let base = D::k(1.0).add(factor_d(w).scale(t).div(s));
    base.powv(decay_d(w))
}
fn s0_d(w: &[D; NP], g: u32) -> D {
    w[s0_idx(g)]
}
fn d0_raw_d(w: &[D; NP], g: f64) -> D {
    w[4].sub(w[5].scale(g - 1.0).exp()).shift(1.0)
}
fn d0_d(w: &[D; NP], g: f64) -> D {
    d0_raw_d(w, g).clamp(1.0, 10.0)
}
fn next_d_d(w: &[D; NP], d: D, g: f64) -> D {
    let one = D::k(1.0);
    let delta = w[6].scale(-(g - 3.0)); // -w6·(g-3)
    let arg2 = d.add(D::k(10.0).sub(d).mul(delta).scale(1.0 / 9.0)); // d + (10-d)·delta/9
    let arg1 = d0_raw_d(w, 4.0); // D0(Easy) 未夹（均值回归目标）
    one.sub(w[7]).mul(arg2).add(w[7].mul(arg1)).clamp(1.0, 10.0) // w7·arg1+(1-w7)·arg2
}
fn next_s_ok_d(w: &[D; NP], s: D, d: D, r: D, g: u32) -> D {
    let one = D::k(1.0);
    let hard = if g == 2 { w[15] } else { one };
    let easy = if g == 4 { w[16] } else { one };
    let inner = w[10].mul(one.sub(r)).exp().sub(one); // exp((1-R)·w10)-1
    let inc = w[8]
        .exp()
        .mul(D::k(11.0).sub(d))
        .mul(s.powv(w[9].neg())) // s^(-w9)
        .mul(inner)
        .mul(hard)
        .mul(easy);
    s.mul(inc.add(one)).clamp(MIN_STABILITY_FLOOR, MAX_STABILITY)
}
fn next_s_lapse_d(w: &[D; NP], s: D, d: D, r: D) -> D {
    let one = D::k(1.0);
    let long = w[11]
        .mul(d.powv(w[12].neg())) // d^(-w12)
        .mul(s.add(one).powv(w[13]).sub(one)) // (s+1)^w13 - 1
        .mul(w[14].mul(one.sub(r)).exp()); // exp((1-R)·w14)
    let short_cap = s.div(w[17].mul(w[18]).exp()); // s / exp(w17·w18)
    // min = 按值取较小支，梯度只走被激活的那一支
    let chosen = if long.v <= short_cap.v { long } else { short_cap };
    floor_s(chosen)
}
fn next_s_short_d(w: &[D; NP], s: D, g: f64) -> D {
    let e = w[17].mul(D::k(g - 3.0).add(w[18])); // w17·(g-3+w18)
    let inc0 = e.exp().mul(s.powv(w[19].neg())); // exp(·)·s^(-w19)
    // max(inc,1)：按值取较大支，梯度走激活支（仅 g>=2 时抬）
    let inc = if g >= 2.0 {
        if inc0.v >= 1.0 {
            inc0
        } else {
            D::k(1.0)
        }
    } else {
        inc0
    };
    floor_s(s.mul(inc))
}
/// 稳定度下限夹取：越下界→常量、梯度 0；否则原样透传（对齐 coerceAtLeast）。
fn floor_s(x: D) -> D {
    if x.v < MIN_STABILITY_FLOOR {
        D::k(MIN_STABILITY_FLOOR)
    } else {
        x
    }
}

/// 单遍前向：返回 (loss, 梯度, 预测桶样本 (pred,label))。`collect_bins=false` 省内存只做梯度。
pub fn forward(items: &[Item], w: &[f64; NP], collect_bins: bool) -> (f64, [f64; NP], Vec<(f64, f64)>) {
    let wd: [D; NP] = std::array::from_fn(|i| D::p(i, w[i]));
    let one = D::k(1.0);
    let mut loss = D::k(0.0);
    let mut n = 0usize;
    let mut preds: Vec<(f64, f64)> = Vec::new();

    for it in items {
        let (mut s, mut d) = (D::k(0.0), D::k(0.0));
        for (idx, rv) in it.iter().enumerate() {
            if idx == 0 {
                // 首评：只初始化，不产生损失
                s = s0_d(&wd, rv.rating);
                d = d0_d(&wd, rv.rating as f64);
                continue;
            }
            if rv.elapsed >= 1.0 {
                let r = recall_d(&wd, s, rv.elapsed).clamp(1e-7, 1.0 - 1e-7);
                let y = if rv.rating >= 2 { 1.0 } else { 0.0 };
                // BCE = -( y·ln r + (1-y)·ln(1-r) )
                let term = D::k(y).mul(r.ln()).add(D::k(1.0 - y).mul(one.sub(r).ln()));
                loss = loss.add(term.neg());
                n += 1;
                if collect_bins {
                    preds.push((r.v, y));
                }
                d = next_d_d(&wd, d, rv.rating as f64);
                s = if rv.rating == 1 {
                    next_s_lapse_d(&wd, s, d, r)
                } else {
                    next_s_ok_d(&wd, s, d, r, rv.rating)
                };
            } else {
                // 同天短期更新：只改 S，不计损失
                s = next_s_short_d(&wd, s, rv.rating as f64);
            }
        }
    }
    if n == 0 {
        return (f64::NAN, [0.0; NP], preds);
    }
    let l = loss.scale(1.0 / n as f64);
    (l.v, l.g, preds)
}

/// 评估：返回 (mean logloss, 10 个校准桶 (pred和, label和, 计数))。
pub fn evaluate(items: &[Item], w: &[f64; NP]) -> (f64, Vec<(f64, f64, u32)>) {
    let (loss, _, preds) = forward(items, w, true);
    let mut bins = vec![(0.0f64, 0.0f64, 0u32); 10];
    for (p, y) in preds {
        let b = ((p * 10.0) as usize).min(9);
        bins[b].0 += p;
        bins[b].1 += y;
        bins[b].2 += 1;
    }
    (loss, bins)
}

/// 训练配置：迭代数、学习率、梯度 L2 范数裁剪阈值、解耦权重衰减（0 即纯 Adam）。
#[derive(Clone, Copy, Debug)]
pub struct TrainCfg {
    pub iters: u32,
    pub lr: f64,
    pub clip: f64,
    pub wd: f64,
}
impl Default for TrainCfg {
    fn default() -> Self {
        Self { iters: 150, lr: 0.02, clip: 1.0, wd: 0.0 }
    }
}

/// AdamW 训练：逐迭代全批前向取梯度，按 L2 范数裁剪，偏差校正 + 解耦权重衰减，
/// 参数夹到 [MIN,MAX]；任一 loss 或梯度非有限即中止。返回 (params, loss_first, loss_last)。
pub fn train(items: &[Item], init: [f64; NP], cfg: &TrainCfg) -> ([f64; NP], f64, f64) {
    let mut w = init;
    let (mut m, mut v) = ([0.0f64; NP], [0.0f64; NP]);
    let (b1, b2, eps) = (0.9f64, 0.999f64, 1e-8f64);
    let (mut first, mut last) = (f64::NAN, f64::NAN);
    for t in 1..=cfg.iters {
        let (l, g, _) = forward(items, &w, false);
        if l.is_nan() {
            break; // NaN 中止：保留上一组可用参数
        }
        if t == 1 {
            first = l;
        }
        last = l;
        let gnorm = g.iter().map(|x| x * x).sum::<f64>().sqrt();
        let cscale = if cfg.clip > 0.0 && gnorm > cfg.clip {
            cfg.clip / gnorm
        } else {
            1.0
        };
        let bc1 = 1.0 - b1.powi(t as i32);
        let bc2 = 1.0 - b2.powi(t as i32);
        let mut nw = w;
        let mut ok = true;
        for i in 0..NP {
            let gi = g[i] * cscale;
            if !gi.is_finite() {
                ok = false;
                break;
            }
            m[i] = b1 * m[i] + (1.0 - b1) * gi;
            v[i] = b2 * v[i] + (1.0 - b2) * gi * gi;
            let mh = m[i] / bc1;
            let vh = v[i] / bc2;
            let upd = cfg.lr * (mh / (vh.sqrt() + eps) + cfg.wd * w[i]);
            let cand = w[i] - upd;
            nw[i] = if cand.is_finite() {
                cand.clamp(MIN[i], MAX[i])
            } else {
                w[i]
            };
        }
        if !ok {
            break;
        }
        w = nw;
    }
    (w, first, last)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed_item() -> Item {
        vec![
            Rev { rating: 3, elapsed: 0.0, sched: 0.0, state: 0 }, // 首评：只初始化
            Rev { rating: 3, elapsed: 2.0, sched: 1.0, state: 1 }, // elapsed>=1 → 计入损失
            Rev { rating: 1, elapsed: 4.0, sched: 5.0, state: 2 }, // lapse → 计入损失
            Rev { rating: 4, elapsed: 1.0, sched: 10.0, state: 2 }, // ok → 计入损失
            Rev { rating: 2, elapsed: 0.5, sched: 1.0, state: 2 }, // elapsed<1 → 短期，无损失
        ]
    }

    /// 值一致：dual 前向的 .v 必须等于纯 Double 前向（autodiff 值 ≡ plain 值）。
    #[test]
    fn dual_matches_plain_forward_value() {
        let items = vec![fixed_item()];
        let (loss_d, _grad, preds_d) = forward(&items, &DEFAULT_PARAMS, true);
        let (loss_p, preds_p) = forward_val(&items, &DEFAULT_PARAMS);
        assert!(loss_d.is_finite(), "dual loss must be finite");
        assert!(
            (loss_d - loss_p).abs() < 1e-9,
            "loss mismatch dual {} plain {}",
            loss_d,
            loss_p
        );
        assert_eq!(preds_d.len(), preds_p.len());
        assert_eq!(preds_d.len(), 3, "应有 3 个 elapsed>=1 损失项");
        for (a, b) in preds_d.iter().zip(preds_p.iter()) {
            assert!((a.0 - b.0).abs() < 1e-9, "pred value mismatch {:?} {:?}", a, b);
            assert_eq!(a.1, b.1);
        }
    }

    /// 梯度有限，且 decay 参 w[20] 应拿到非零梯度（v6 相对 v5 的关键可学习项）。
    #[test]
    fn gradient_finite_and_decay_learnable() {
        let items = vec![fixed_item()];
        let (_l, g, _p) = forward(&items, &DEFAULT_PARAMS, false);
        assert!(g.iter().all(|x| x.is_finite()), "grad must be finite");
        assert!(g[20].abs() > 1e-12, "w20(decay) gradient should be nonzero");
    }

    /// evaluate 返回 loss + 10 桶，桶内样本总数 == 损失项数。
    #[test]
    fn evaluate_returns_loss_and_10_bins() {
        let items = vec![fixed_item()];
        let (loss, bins) = evaluate(&items, &DEFAULT_PARAMS);
        assert!(loss.is_finite());
        assert_eq!(bins.len(), 10);
        let total: u32 = bins.iter().map(|b| b.2).sum();
        assert_eq!(total, 3);
    }

    /// 合成数据（由已知参数生成）上训练：loss 必须下降，参数保持有限且在界内。
    #[test]
    fn train_reduces_loss_on_synthetic() {
        let items = synth_items(500, &DEFAULT_PARAMS);
        let mut init = DEFAULT_PARAMS;
        for i in 0..NP {
            init[i] = (init[i] * 1.3 + 0.02).clamp(MIN[i], MAX[i]);
        }
        let cfg = TrainCfg { iters: 60, lr: 0.02, clip: 1.0, wd: 0.0 };
        let (w, first, last) = train(&items, init, &cfg);
        assert!(first.is_finite() && last.is_finite(), "loss must be finite");
        assert!(last < first, "loss must drop: first {} last {}", first, last);
        assert!(w.iter().all(|x| x.is_finite()));
        for i in 0..NP {
            assert!(w[i] >= MIN[i] - 1e-9 && w[i] <= MAX[i] + 1e-9, "param {} out of bounds {}", i, w[i]);
        }
    }

    /// 用已知参数生成可被拟合的合成复习历史：label 由真实 recall 决定。
    fn synth_items(n_cards: usize, w: &[f64; NP]) -> Vec<Item> {
        let mut rng = Lcg { state: 0x1234_5678_9abc_def0 };
        let mut out = Vec::with_capacity(n_cards);
        for _ in 0..n_cards {
            let g0 = (rng.below(4) + 1) as u32; // 1..4
            let mut s = w[(g0 - 1) as usize];
            let mut d = d0_v(w, g0 as f64);
            let mut it: Item = vec![Rev { rating: g0, elapsed: 0.0, sched: 0.0, state: 0 }];
            let steps = 2 + rng.below(4); // 2..5 次后续复习
            let mut prev_sched = 1.0f64;
            for _ in 0..steps {
                let t = 1.0 + rng.below(20) as f64; // elapsed ∈ [1,21] ≥1 → 均计入损失
                let r = recall_v(w, s, t).clamp(1e-7, 1.0 - 1e-7);
                let rating: u32 = if rng.next_f64() < r { 3 } else { 1 };
                it.push(Rev { rating, elapsed: t, sched: prev_sched, state: 2 });
                prev_sched = t;
                d = next_d_v(w, d, rating as f64);
                s = if rating == 1 {
                    next_s_lapse_v(w, s, d, r)
                } else {
                    next_s_ok_v(w, s, d, r, rating)
                };
            }
            out.push(it);
        }
        out
    }

    /// 极简确定性线性同余，保证测试可复现（不用外部随机）。
    struct Lcg {
        state: u64,
    }
    impl Lcg {
        fn next_u64(&mut self) -> u64 {
            self.state = self
                .state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            self.state
        }
        fn below(&mut self, m: u64) -> u64 {
            self.next_u64() % m
        }
        fn next_f64(&mut self) -> f64 {
            (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
        }
    }
}
