//! fsrsjni：StudyKit v2.9 端侧 FSRS v6（21 参）优化器的 Rust JNI 层。
//! 提供 nativeTrain / nativeEvaluate 两个导出，供 Kotlin 绑定 `com.studykit.optimize.FsrsNative` 调用。
// 本 crate 对外保留一套完整、且单测覆盖的 API：Double 参照实现(forward_val 等)作为值一致/回归
// 基座、powk 作为自动微分标准算子、Rev.sched/state 作为 JNI 契约字段——非 test 构建下暂未被内部
// 调用属预期，故整体豁免 dead_code 提示。
#![allow(dead_code)]
// R2 起这两个模块对外可见：`src/bin/gen_fixture.rs` 要直接调 core 的纯 Double 前向函数产出口径
// 一致 fixture（见 core.rs 顶部注释）。本 crate 不发布到 crates.io，仅本机/NDK 构建自用，扩大
// 可见性不引入外部 API 负担；JNI 契约仍只是下面那两个 `#[no_mangle]` 导出。
pub mod core;
pub mod dual;

use crate::core::{evaluate, train, Item, Rev, TrainCfg, DEFAULT_PARAMS};
use crate::dual::NP;
use jni::objects::{JClass, JFloatArray, JIntArray};
use jni::sys::jfloatArray;
use jni::JNIEnv;

/// 打包格式（Kotlin → Rust）：
/// reviews: FloatArray，每条 review 4 float = [rating, elapsed_days, scheduled_days, state]
///          state: 0=New 1=Learning 2=Review 3=Relearning
/// bounds:  IntArray 前缀和 [0, n1, n1+n2, ...]，len = n_items+1
/// 约束：items 必须按「首评时间」升序传入（时序切分依赖此顺序）
fn parse(env: &mut JNIEnv, reviews: &JFloatArray, bounds: &JIntArray) -> Option<Vec<Item>> {
    let rl = env.get_array_length(reviews).ok()? as usize;
    let bl = env.get_array_length(bounds).ok()? as usize;
    if bl < 1 {
        return None;
    }
    let mut rb = vec![0f32; rl];
    env.get_float_array_region(reviews, 0, &mut rb).ok()?;
    let mut bb = vec![0i32; bl];
    env.get_int_array_region(bounds, 0, &mut bb).ok()?;
    let mut items = Vec::with_capacity(bl - 1);
    for w in bb.windows(2) {
        let (a, b) = (w[0] as usize * 4, w[1] as usize * 4);
        if b > rl || a > b {
            return None;
        }
        items.push(
            rb[a..b]
                .chunks_exact(4)
                .map(|c| Rev {
                    rating: c[0] as u32,
                    elapsed: c[1] as f64,
                    sched: c[2] as f64,
                    state: c[3] as u32,
                })
                .collect::<Item>(),
        );
    }
    Some(items)
}

/// 把 f32 向量装成一个新的 jfloatArray；分配失败则回退为空数组（Kotlin 侧据此判失败）。
fn out(env: &mut JNIEnv, vals: Vec<f32>) -> jfloatArray {
    match env.new_float_array(vals.len() as i32) {
        Ok(arr) => {
            if env.set_float_array_region(&arr, 0, &vals).is_err() {
                return std::ptr::null_mut();
            }
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// 返回 FloatArray[21+5]：params[21] + [loss_first, loss_last, n_items, n_terms, ms]。
/// 任何 panic / 解析失败 → 空数组，Kotlin 侧保留旧参数。
#[no_mangle]
pub extern "system" fn Java_com_studykit_optimize_FsrsNative_nativeTrain(
    mut env: JNIEnv,
    _: JClass,
    reviews: JFloatArray,
    bounds: JIntArray,
    init: JFloatArray,
    cfg: JIntArray,
) -> jfloatArray {
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let t0 = std::time::Instant::now();
        let items = parse(&mut env, &reviews, &bounds)?;
        let il = env.get_array_length(&init).ok()?;
        let mut w = DEFAULT_PARAMS;
        if il as usize == NP {
            let mut b = vec![0f32; NP];
            env.get_float_array_region(&init, 0, &mut b).ok()?;
            for i in 0..NP {
                w[i] = b[i] as f64;
            }
        }
        let mut ci = vec![0i32; 2];
        env.get_int_array_region(&cfg, 0, &mut ci).ok()?;
        let c = TrainCfg {
            iters: ci[0].max(1) as u32,
            ..Default::default()
        };
        let n_terms: usize = items.iter().map(|it| it.len().saturating_sub(1)).sum();
        let (w, f, l) = train(&items, w, &c);
        Some((
            w,
            f,
            l,
            items.len(),
            n_terms,
            t0.elapsed().as_millis() as f64,
        ))
    }));
    match r {
        Ok(Some((w, f, l, ni, nt, ms))) => {
            let mut v: Vec<f32> = w.iter().map(|&x| x as f32).collect();
            v.extend([f as f32, l as f32, ni as f32, nt as f32, ms as f32]);
            out(&mut env, v)
        }
        _ => out(&mut env, vec![]), // 空数组 = 失败
    }
}

/// 返回 FloatArray[2+30]：[logloss, n_terms] + 10 桶 × (pred均值, obs均值, count)。
#[no_mangle]
pub extern "system" fn Java_com_studykit_optimize_FsrsNative_nativeEvaluate(
    mut env: JNIEnv,
    _: JClass,
    reviews: JFloatArray,
    bounds: JIntArray,
    params: JFloatArray,
) -> jfloatArray {
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let items = parse(&mut env, &reviews, &bounds)?;
        let mut b = vec![0f32; NP];
        env.get_float_array_region(&params, 0, &mut b).ok()?;
        let w: [f64; NP] = std::array::from_fn(|i| b[i] as f64);
        let (loss, bins) = evaluate(&items, &w);
        let n: u32 = bins.iter().map(|x| x.2).sum();
        let mut v = vec![loss as f32, n as f32];
        for (ps, ys, c) in bins {
            v.extend([
                if c > 0 { (ps / c as f64) as f32 } else { -1.0 },
                if c > 0 { (ys / c as f64) as f32 } else { -1.0 },
                c as f32,
            ]);
        }
        Some(v)
    }));
    match r {
        Ok(Some(v)) => out(&mut env, v),
        _ => out(&mut env, vec![]),
    }
}
