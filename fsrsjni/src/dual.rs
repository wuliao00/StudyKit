//! 前向模式自动微分：每个数携带 21 维梯度（FSRS v6）。
//! 一次前向同时算出函数值与对全部 21 个参数的偏导，1 万条 review 单步梯度可接受。
//! 结构体与算子签名先定义，测试先写（TDD RED），随后补 impl。
pub const NP: usize = 21;

/// 带梯度的标量：`v` = 函数值，`g[i]` = ∂v/∂w[i]。
#[derive(Clone, Copy)]
pub struct D {
    pub v: f64,
    pub g: [f64; NP],
}

impl D {
    /// 常量：值为 x，梯度全 0。
    pub const fn k(x: f64) -> D {
        D { v: x, g: [0.0; NP] }
    }

    /// 第 i 个参数本身：值为 x，仅 g[i]=1。
    pub fn p(i: usize, x: f64) -> D {
        let mut d = D::k(x);
        d.g[i] = 1.0;
        d
    }

    pub fn add(self, o: D) -> D {
        let mut g = self.g;
        for i in 0..NP {
            g[i] += o.g[i];
        }
        D { v: self.v + o.v, g }
    }

    pub fn sub(self, o: D) -> D {
        let mut g = self.g;
        for i in 0..NP {
            g[i] -= o.g[i];
        }
        D { v: self.v - o.v, g }
    }

    pub fn mul(self, o: D) -> D {
        let mut g = self.g;
        for i in 0..NP {
            g[i] = g[i] * o.v + self.v * o.g[i];
        }
        D { v: self.v * o.v, g }
    }

    pub fn div(self, o: D) -> D {
        let mut g = self.g;
        let o2 = o.v * o.v;
        for i in 0..NP {
            g[i] = (g[i] * o.v - self.v * o.g[i]) / o2;
        }
        D { v: self.v / o.v, g }
    }

    pub fn scale(self, f: f64) -> D {
        let mut g = self.g;
        for x in g.iter_mut() {
            *x *= f;
        }
        D { v: self.v * f, g }
    }

    pub fn shift(self, f: f64) -> D {
        D { v: self.v + f, g: self.g }
    }

    pub fn neg(self) -> D {
        let mut g = self.g;
        for x in g.iter_mut() {
            *x = -*x;
        }
        D { v: -self.v, g }
    }

    pub fn exp(self) -> D {
        let e = self.v.exp();
        let mut g = self.g;
        for x in g.iter_mut() {
            *x *= e;
        }
        D { v: e, g }
    }

    pub fn ln(self) -> D {
        let inv = 1.0 / self.v;
        let mut g = self.g;
        for x in g.iter_mut() {
            *x *= inv;
        }
        D { v: self.v.ln(), g }
    }

    /// 变底变指：self^e = exp(e·ln self)，两侧梯度都参与链式法则。
    pub fn powv(self, e: D) -> D {
        e.mul(self.ln()).exp()
    }

    /// 变底常指：self^k = exp(k·ln self)。
    pub fn powk(self, k: f64) -> D {
        D::k(k).mul(self.ln()).exp()
    }

    /// 边界夹断：越界时输出常量、梯度置 0（次梯度口径）。
    pub fn clamp(self, lo: f64, hi: f64) -> D {
        if self.v < lo {
            D::k(lo)
        } else if self.v > hi {
            D::k(hi)
        } else {
            self
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{D, NP};

    /// 生成确定性的"满梯度"测试向量：g[k] = (k+1)*p*0.1
    fn grad(p: f64) -> [f64; NP] {
        let mut g = [0.0f64; NP];
        for (k, x) in g.iter_mut().enumerate() {
            *x = (k as f64 + 1.0) * p * 0.1;
        }
        g
    }

    #[test]
    fn k_has_zero_gradient() {
        let d = D::k(3.25);
        assert_eq!(d.v, 3.25);
        assert!(d.g.iter().all(|&x| x == 0.0));
    }

    #[test]
    fn p_is_onehot() {
        let d = D::p(7, 1.5);
        assert_eq!(d.v, 1.5);
        for i in 0..NP {
            assert_eq!(d.g[i], if i == 7 { 1.0 } else { 0.0 });
        }
    }

    #[test]
    fn add_value_and_grad() {
        let a = D { v: 2.5, g: grad(1.0) };
        let b = D { v: -1.0, g: grad(2.0) };
        let r = a.add(b);
        assert_eq!(r.v, 1.5);
        for i in 0..NP {
            assert!((r.g[i] - (a.g[i] + b.g[i])).abs() < 1e-12);
        }
    }

    #[test]
    fn sub_value_and_grad() {
        let a = D { v: 5.0, g: grad(1.0) };
        let b = D { v: 3.0, g: grad(2.0) };
        let r = a.sub(b);
        assert_eq!(r.v, 2.0);
        for i in 0..NP {
            assert!((r.g[i] - (a.g[i] - b.g[i])).abs() < 1e-12);
        }
    }

    #[test]
    fn mul_product_rule() {
        let a = D { v: 2.0, g: grad(1.0) };
        let b = D { v: 3.0, g: grad(2.0) };
        let r = a.mul(b);
        assert_eq!(r.v, 6.0);
        for i in 0..NP {
            let expect = a.g[i] * b.v + a.v * b.g[i];
            assert!((r.g[i] - expect).abs() < 1e-12);
        }
    }

    #[test]
    fn div_quotient_rule() {
        let a = D { v: 6.0, g: grad(1.0) };
        let b = D { v: 2.0, g: grad(2.0) };
        let r = a.div(b);
        assert_eq!(r.v, 3.0);
        let b2 = b.v * b.v;
        for i in 0..NP {
            let expect = (a.g[i] * b.v - a.v * b.g[i]) / b2;
            assert!((r.g[i] - expect).abs() < 1e-12);
        }
    }

    #[test]
    fn scale_and_shift_and_neg() {
        let a = D { v: 2.0, g: grad(1.0) };
        let s = a.scale(3.0);
        assert_eq!(s.v, 6.0);
        for i in 0..NP {
            assert!((s.g[i] - a.g[i] * 3.0).abs() < 1e-12);
        }
        let sh = a.shift(1.5);
        assert_eq!(sh.v, 3.5);
        for i in 0..NP {
            assert!((sh.g[i] - a.g[i]).abs() < 1e-12, "shift must not touch grad");
        }
        let n = a.neg();
        assert_eq!(n.v, -2.0);
        for i in 0..NP {
            assert!((n.g[i] + a.g[i]).abs() < 1e-12);
        }
    }

    #[test]
    fn exp_chain() {
        let a = D { v: 1.3, g: grad(1.0) };
        let r = a.exp();
        let e = a.v.exp();
        assert!((r.v - e).abs() < 1e-12);
        for i in 0..NP {
            assert!((r.g[i] - a.g[i] * e).abs() < 1e-12);
        }
    }

    #[test]
    fn ln_chain() {
        let a = D { v: 2.7, g: grad(1.0) };
        let r = a.ln();
        assert!((r.v - a.v.ln()).abs() < 1e-12);
        for i in 0..NP {
            assert!((r.g[i] - a.g[i] / a.v).abs() < 1e-12);
        }
    }

    #[test]
    fn powk_const_exponent() {
        let e = 2.5;
        let a = D { v: 3.0, g: grad(1.0) };
        let r = a.powk(e);
        assert!((r.v - a.v.powf(e)).abs() < 1e-9);
        let deriv = e * a.v.powf(e - 1.0);
        for i in 0..NP {
            assert!((r.g[i] - a.g[i] * deriv).abs() < 1e-8);
        }
    }

    #[test]
    fn powv_var_base_var_exp() {
        let base = D { v: 2.0, g: grad(1.0) };
        let expn = D { v: 3.0, g: grad(2.0) };
        let r = base.powv(expn);
        assert!((r.v - base.v.powf(expn.v)).abs() < 1e-9);
        // d/dp (base^exp) = base^exp * (exp' * ln(base) + exp * base'/base)
        for i in 0..NP {
            let expect = r.v * (expn.g[i] * base.v.ln() + expn.v * base.g[i] / base.v);
            assert!((r.g[i] - expect).abs() < 1e-7, "powv grad idx {}", i);
        }
    }

    #[test]
    fn clamp_in_range_passes_grad() {
        let a = D { v: 5.0, g: grad(1.0) };
        let r = a.clamp(1.0, 10.0);
        assert_eq!(r.v, 5.0);
        for i in 0..NP {
            assert!((r.g[i] - a.g[i]).abs() < 1e-12);
        }
    }

    #[test]
    fn clamp_out_of_range_is_const_zero_grad() {
        let lo = D { v: -3.0, g: grad(1.0) };
        let rlo = lo.clamp(0.0, 10.0);
        assert_eq!(rlo.v, 0.0);
        assert!(rlo.g.iter().all(|&x| x == 0.0), "below-lo must kill grad");
        let hi = D { v: 42.0, g: grad(1.0) };
        let rhi = hi.clamp(0.0, 10.0);
        assert_eq!(rhi.v, 10.0);
        assert!(rhi.g.iter().all(|&x| x == 0.0), "above-hi must kill grad");
    }

    /// 复合表达式的中心差分：验证链式法则把各算子梯度正确组合（值 vs 数值导数）。
    #[test]
    fn composite_finite_difference_chain() {
        // f(x) = (x*exp(x) + 1) / ln(x)   at x=2.2 on param index 5
        let idx = 5usize;
        let x = 2.2f64;
        let f = |xx: f64| (xx * xx.exp() + 1.0) / xx.ln();
        let a = D::p(idx, x);
        let num = a.mul(a.exp()).shift(1.0);
        let den = a.ln();
        let r = num.div(den);
        // 值对齐
        assert!((r.v - f(x)).abs() < 1e-9);
        // 梯度对齐中心差分
        let h = 1e-5;
        let numeric = (f(x + h) - f(x - h)) / (2.0 * h);
        assert!(
            (r.g[idx] - numeric).abs() < 1e-4,
            "fd mismatch: analytic {} numeric {}",
            r.g[idx],
            numeric
        );
        // 其余分量应为 0
        for i in 0..NP {
            if i != idx {
                assert_eq!(r.g[i], 0.0, "unrelated grad idx {} must be 0", i);
            }
        }
    }
}
