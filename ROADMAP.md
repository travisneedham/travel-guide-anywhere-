# Roadmap

Phase-level development status for Travel Guide Anywhere. This lives **in the repo** so it
survives a fresh clone and is portable to any AI tool or machine. It is the durable companion to:

- `ENGINEERING_NOTES.md` — *how* things work (algorithms, bug post-mortems, decisions).
- `CLAUDE.md` — workflow, versioning, and toolchain constraints.

Keep this file updated whenever a phase closes or a new direction is chosen. Most recent first.

---

## ✅ Phase 1 — Famous-Mode POI Ranking & Diversity (COMPLETE, v3.5.x)

**Outcome:** Famous mode now surfaces the real headliners in a sensible order and interleaves
categories so the tour no longer opens with four stadiums in a row. Working well and validated
against real DFW logs.

Shipped:
- **Coverage:** raised the Overpass output cap 300 → 1200 (`FAMOUS_SHARD_CAP`). This was the
  single biggest fix — the old cap silently dropped headliners. ~296 → ~1163 POIs in DFW.
- **Ranking:** rebalanced `fameScore` (power-curve pageviews, capped sitelinks, weak un-enriched
  fallback, OTHER demoted) so real landmarks beat infrastructure/agencies and mis-tagged map-dots.
- **Reclassification:** `heritage`-tagged buildings with no `historic` tag now resolve to
  `HISTORIC` instead of `OTHER`.
- **Diversity:** two-level interleaving — finer `diversityKey` (stadium/ride/zoo/tower/…) +
  a `DIVERSITY_WINDOW = 3` recent-key deque that floats fresh categories forward while preserving
  fame order within each half. First pick is still the #1 headliner.
- **Settings:** nested interest sub-category filters; removed the POI API Experiment; added
  Session-History and Ranked-POI export buttons (TSV) in NERD STUFF for auditing.

Full algorithm write-up: `ENGINEERING_NOTES.md` → "Famous-Mode POI Ranking & Diversity Algorithm
(Phase 1 checkpoint)".

---

## 🔜 Next / Deferred

Not blocking Phase 1; pick up in a future entry.

- **Small ranking/diversity bugs.** Known minor issues to troubleshoot (to be itemized when
  tackled). Core approach is sound — re-tune against logs, not intuition.
- **Coaster collapse (partial).** Six Flags coasters share `diversityKey = "ride"`, so the window
  de-clusters them, but multiple individual coasters can still each rank/appear. A true
  same-venue collapse (operator/spatial) was deferred — the operator-tag approach failed because
  the coasters carry inconsistent `operator` values.
- **First-install interest selection (future feature).** Prompt the user on first install to
  select — and ideally rank — what they're interested in, then weight POI selection accordingly.
  The nested sub-category filters shipped in Phase 1 are the building block for this.
