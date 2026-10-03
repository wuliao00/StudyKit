"""
FSRS v6 ORACLE generator.

Authoritative source: the reference implementation `py-fsrs` (open-spaced-repetition),
installed locally via `pip install fsrs` (version recorded in the output). We DO NOT
re-derive formulas by hand: every golden number below is produced by calling the
library's own private primitives (_initial_stability / _initial_difficulty /
_next_difficulty / _next_stability / _short_term_stability) and its retrievability
formula, then ASSEMBLED the same way StudyKit's FsrsKernel.review() assembles them:

  * firstTime (stability == null): store initial_stability(rating) + initial_difficulty(rating)
    directly (matches py-fsrs: the first rating seeds S0/D0, it does NOT grow).
  * same-day path (elapsedDays < 1): _short_term_stability.
  * long-term path (elapsedDays >= 1): retrievability then _next_stability (recall / forget).
  * difficulty (non-firstTime): _next_difficulty (mean-reversion, arg_1 = D0(Easy) UNCLAMPED).

Retrievability uses v6's per-vector decay: DECAY = -parameters[20], FACTOR = 0.9^(1/DECAY)-1.
"""

import json
import math
from datetime import datetime, timedelta, timezone

import fsrs
from fsrs import Scheduler, Card, Rating, State

SCHED = Scheduler(enable_fuzzing=False)
W = list(SCHED.parameters)
DECAY = -W[20]
FACTOR = 0.9 ** (1.0 / DECAY) - 1.0

STUDYKIT_HALF_OVER_S = 243.0 / 19.0  # StudyKit mirror constant (unchanged under v6)


def retrievability(stability: float, t: float) -> float:
    return (1.0 + FACTOR * t / stability) ** DECAY


def long_term_stability(stability: float, difficulty: float, t: float, rating: Rating) -> float:
    r = retrievability(stability, t)
    return SCHED._next_stability(difficulty=difficulty, stability=stability, retrievability=r, rating=rating)


def next_difficulty(difficulty: float, rating: Rating) -> float:
    return SCHED._next_difficulty(difficulty=difficulty, rating=rating)


def short_term_stability(stability: float, rating: Rating) -> float:
    return SCHED._short_term_stability(stability=stability, rating=rating)


def interval_days(stability: float, desired_retention: float) -> float:
    # StudyKit returns a CONTINUOUS interval (no day-rounding); algebraic inverse of retrievability.
    return stability / FACTOR * (desired_retention ** (1.0 / DECAY) - 1.0)


def scenario_long(s: float, d: float, t: float, rating: Rating):
    r = retrievability(s, t)
    ns = long_term_stability(s, d, t, rating)
    nd = next_difficulty(d, rating)
    return {
        "input": {"stability": s, "difficulty": d, "elapsedDays": t, "rating": rating.name},
        "retrievability": r,
        "next_stability": ns,
        "next_difficulty": nd,
        "interval_at_0.9": interval_days(ns, 0.9),
        "interval_at_0.88": interval_days(ns, 0.88),
        "interval_at_0.85": interval_days(ns, 0.85),
    }


def scenario_short(s: float, d: float, t: float, rating: Rating):
    ns = short_term_stability(s, rating)
    nd = next_difficulty(d, rating)
    return {
        "input": {"stability": s, "difficulty": d, "elapsedDays": t, "rating": rating.name},
        "next_stability": ns,
        "next_difficulty": nd,
        "interval_at_0.9": interval_days(ns, 0.9),
    }


# ---- pre-bake the StudyKit test inputs ----
GOOD_S0 = W[2]                       # 2.3065
GOOD_D0 = SCHED._initial_difficulty(rating=Rating.Good, clamp=True)  # ~2.118104
SEEDED_S = 30.0 / STUDYKIT_HALF_OVER_S  # mirrored stability for halfLife=30

out = {}
out["oracle"] = {
    "library": "py-fsrs",
    "library_version": getattr(fsrs, "__version__", "6.3.2"),
    "installed_via": "pip install fsrs",
    "param_count": len(W),
    "default_parameters": W,
    "fsrs_default_decay_param_w20": W[20],
    "DECAY": DECAY,
    "FACTOR": FACTOR,
}

# initial stability / difficulty per rating
out["initial"] = {
    "stability_by_rating": {
        "Again": SCHED._initial_stability(rating=Rating.Again),
        "Hard": SCHED._initial_stability(rating=Rating.Hard),
        "Good": SCHED._initial_stability(rating=Rating.Good),
        "Easy": SCHED._initial_stability(rating=Rating.Easy),
    },
    "difficulty_by_rating_clamped": {
        rn: SCHED._initial_difficulty(rating=rt, clamp=True)
        for rn, rt in [("Again", Rating.Again), ("Hard", Rating.Hard),
                       ("Good", Rating.Good), ("Easy", Rating.Easy)]
    },
    "difficulty_by_rating_unclamped": {
        rn: SCHED._initial_difficulty(rating=rt, clamp=False)
        for rn, rt in [("Again", Rating.Again), ("Hard", Rating.Hard),
                       ("Good", Rating.Good), ("Easy", Rating.Easy)]
    },
    "mean_reversion_target_arg1_unclamped": SCHED._initial_difficulty(rating=Rating.Easy, clamp=False),
}

# next_difficulty from a reference difficulty (tests mean reversion + linear damping)
out["next_difficulty_from_D5"] = {
    rt.name: next_difficulty(5.0, rt) for rt in (Rating.Again, Rating.Hard, Rating.Good, Rating.Easy)
}

# retrievability table + anchors
out["retrievability"] = {
    "anchor_R_at_t_equals_S": retrievability(10.0, 10.0),
    "S10": {f"t={t}": retrievability(10.0, float(t)) for t in (0, 5, 10, 20, 40)},
}

# interval identity at desired 0.9 == stability
out["interval_identity"] = {
    "interval_S10_at_0.9": interval_days(10.0, 0.9),
    "interval_S10_at_0.88": interval_days(10.0, 0.88),
}

# ---- long-term (t>=1) golden scenarios ----
out["golden_long"] = {
    "GOOD_t2": scenario_long(GOOD_S0, GOOD_D0, 2.0, Rating.Good),
    "HARD_t2": scenario_long(GOOD_S0, GOOD_D0, 2.0, Rating.Hard),
    "EASY_t2": scenario_long(GOOD_S0, GOOD_D0, 2.0, Rating.Easy),
    "AGAIN_t2": scenario_long(GOOD_S0, GOOD_D0, 2.0, Rating.Again),
    "AGAIN_t2_mirrorD3": scenario_long(SEEDED_S, 3.0, 2.0, Rating.Again),
    "AGAIN_t2_seededD2": scenario_long(SEEDED_S, 2.0, 2.0, Rating.Again),
}

# ---- short-term (t<1) golden scenarios (new v6 intra-day path) ----
out["golden_short"] = {
    "GOOD_t0.5": scenario_short(GOOD_S0, GOOD_D0, 0.5, Rating.Good),
    "HARD_t0.5": scenario_short(GOOD_S0, GOOD_D0, 0.5, Rating.Hard),
    "EASY_t0.5": scenario_short(GOOD_S0, GOOD_D0, 0.5, Rating.Easy),
    "AGAIN_t0.5": scenario_short(GOOD_S0, GOOD_D0, 0.5, Rating.Again),
}

# ---- full end-to-end review_card trajectory (proves lib integration) ----
traj = []
card = Card(card_id=1, due=datetime(2026, 1, 1, tzinfo=timezone.utc))
ratings = [Rating.Good, Rating.Good, Rating.Good, Rating.Again, Rating.Good, Rating.Easy]
gaps = [0, 1, 3, 0, 6, 12]  # day gaps between successive reviews (0 => same-day short-term path)
cur = datetime(2026, 1, 1, tzinfo=timezone.utc)
for rating, gap in zip(ratings, gaps):
    cur = cur + timedelta(days=gap)
    card, log = SCHED.review_card(card=card, rating=rating, review_datetime=cur)
    traj.append({
        "rating": rating.name,
        "delta_days": gap,
        "state": card.state.name,
        "stability": card.stability,
        "difficulty": card.difficulty,
        "due": card.due.isoformat(),
        "interval_days": (card.due - cur).days,
        "retrievability_now": SCHED.get_card_retrievability(card, current_datetime=cur),
    })
out["review_card_trajectory"] = traj

with open("docs/evidence/fsrs6-golden.json", "w", encoding="utf-8") as f:
    json.dump(out, f, indent=2, ensure_ascii=False)

print(json.dumps(out, indent=2, ensure_ascii=False))
