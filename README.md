# An Interpretable Pharmacogenomics Psychotropic Metabolizer Engine

</br>

<div align="center">
  <img src="client/src/main/resources/brand/logo.png" align="center" width="75%"/>
</div>

</br>
</br>

> BMI 530: Software Development in Biomedical Informatics

A pharmacogenomics prototype that maps a reported star-allele diplotype to a
metabolizer phenotype, then matches that phenotype to published gene–drug
recommendations. It covers **CYP2C19, CYP2D6 and CYP2B6**, and answers for
every drug CPIC covers for those genes — 2,159 recommendations over 18,678
diplotypes, built from CPIC v1.61.0. The written analysis focuses on six
psychotropics: paroxetine, fluvoxamine, venlafaxine, vortioxetine,
escitalopram and sertraline.

What it deliberately does **not** do is combine genes into a composite score
or rank drugs best-to-worst. That layer is what distinguishes a commercial
combinatorial test from the guidelines underneath it, and it is also the
layer the evidence does not support. The engine reports the cited
recommendation, names the genes that decided it, links the guideline, and
stops — including when the guideline's answer is "no recommendation", which
is reported as a result rather than hidden as a blank.

> [!IMPORTANT]
> *Educational prototype — not for clinical use or medical decisions.*

---

## Overview

### UI (JavaFX)

<img width="1235" height="848" src="https://github.com/user-attachments/assets/f7d4f927-9c50-4aae-b3e7-a04e91881834" />

## Where to download

A packaged build needs nothing installed — no Java, no Python, and is located under [releases](https://github.com/PrideInt/PGxEngine/releases):

It starts its own engine on the loopback interface, picks a free port, and
stops the engine when the window closes. To build it, see
[Distributions](#distributions).

---

## Running from source

You could also run the engine application directly from source rather than using a packaged build.

### Requirements

| | |
| --- | --- |
| Python | 3.11 or newer |
| Java | JDK 17 or newer (client only) |
| Everything else | `pip install -r requirements.txt` |

JavaFX is fetched by Maven; nothing to install by hand.

### 1. Build the knowledge base

The database is generated, not committed. Building it needs network access to
`api.cpicpgx.org`.

```bash
# Optional: check the live column names before trusting the schema
python -m pgx.query.cpic --inspect

python -m pgx.query.cpic \
    --genes CYP2C19 CYP2D6 CYP2B6 \
    --cpic-version v1.61.0 \
    --pharmvar-version "via CPIC v1.61.0 (API import)" \
    --out data/pgx.db
```

Pin the versions. They are stamped into the database's `meta` table and
surface in every report's provenance block, which is what makes a result
reproducible months later.

### 2. Query it

**Command line** — one patient at a time, or a CSV batch:

Example:

```bash
python -m pgx.cli --db data/pgx.db --gene CYP2C19=*2/*5 --gene CYP2D6=*4/*4 --pretty
```

```bash
python -m pgx.cli --db data/pgx.db --batch cohort.csv --out reports.jsonl
```

**HTTP service** — FastAPI on loopback:

```bash
PGX_DB=data/pgx.db python -m pgx.service --port 8000
# PowerShell: $env:PGX_DB = "data/pgx.db"
```

| Endpoint | |
| --- | --- |
| `GET /health` | status, row counts, source versions |
| `GET /genes` | loaded genes and their diplotype counts |
| `GET /genes/{gene}/diplotypes?q=&limit=` | searched, never listed whole |
| `GET /drugs?all=` | answerable drugs, or all of them |
| `POST /report` | `{"diplotypes": {"CYP2C19": "*2/*5"}}` |

**Desktop client** — JavaFX, talks to the service over HTTP. Build with Apache Maven:

```bash
cd client
mvn -q clean compile javafx:run
```

`compile` matters: the fonts and stylesheet are resources, and `javafx:run`
alone will not copy them. The client opens on an offline stub if it finds no
engine, so it runs with nothing else started.

---

## Distributions

```bash
bash packaging/build.sh                                      # any platform
powershell -ExecutionPolicy Bypass -File packaging\build.ps1  # Windows only
```

| Output | Needs installed | Runs on |
| --- | --- | --- |
| `dist/PGxEngine-portable/` | Java 17+ | Windows, Linux, Apple Silicon |
| `dist/PGx Engine/` | nothing | the platform it was built on |

Both carry their own frozen engine and a copy of the knowledge base, and both
start the engine themselves. Neither cross-builds: run the script on the
platform you are targeting. See [`packaging/README.md`](packaging/README.md).

---

## Layout

```
pgx/
  models.py        the report contract — schema 1.1
  core/engine.py   resolution and matching. Imports pgx.models and nothing else
  query/cpic.py    build step: CPIC API -> SQLite
  api/main.py      FastAPI wrapper
  service.py       runnable entry point, frozen by PyInstaller
  cli.py           single and batch reports
  notation.py      GeT-RM -> CPIC diplotype notation
  activity.py      CYP2D6 activity-score parsing and banding
  panel.py         masking a diplotype to what a limited assay would report
  frequency.py     Hardy-Weinberg priors and phenotype posteriors
client/            JavaFX client (Java 17, JavaFX 21)
packaging/         build scripts and PyInstaller spec
docs/scope.md      scope, method, findings, limitations
tests/             128 tests, offline against a fixture database
tools/             validation and cohort scripts
```

## Tests

```bash
python -m pytest tests -q
```

No network and no `data/pgx.db` required — the suite builds its own fixture.

## Validation

Diplotypes from the CDC's GeT-RM reference panel resolved 652 of 663 (98.3%),
or 99.4% excluding the seven where GeT-RM itself reports no consensus.
Activity-score banding was checked against CPIC across all 16,836 CYP2D6
diplotypes. A rebuild five weeks later against CPIC v1.61.0 changed no
phenotype and no recommendation for the six drugs in scope.

## Panel restriction

A reported diplotype is not an observation of a genome. It is an observation of
whichever alleles the assay interrogated, with `*1` filled in everywhere else —
so `*1/*1` from a two-allele panel and `*1/*1` from sequencing print
identically and mean different things. Two experiments measure that gap against
GeT-RM, whose true diplotypes are known.

```bash
python tools/panel_experiment.py --db data/pgx.db       # what a panel costs
python tools/posterior_experiment.py --db data/pgx.db   # does modelling it help
```

Phenotype accuracy when each true diplotype is masked down to what a given
panel would have reported, then re-resolved:

| Gene | no panel | core | common | broad |
| --- | --- | --- | --- | --- |
| CYP2C19 | 0.488 | 0.794 | 0.952 | 0.988 |
| CYP2D6 | 0.430 | 0.796 | 0.837 | 0.864 |
| CYP2B6 | 0.463 | 0.860 | 0.904 | 0.912 |

The posterior is deliberately not exposed through the report contract: on these
results it would improve one gene of three and degrade another. 

## The model

### Inference

Write $A$ for the gene's alleles, $f_a$ for the frequency of allele $a$ in a
given population, $S \subseteq A$ for the panel an assay carries, $m_S$ for the
masking map it induces, and $g$ for the map from diplotype to phenotype.

Alleles combine independently within an individual, so diplotype probabilities
follow Hardy-Weinberg:

$$
P(a/b) = \begin{cases}
f_a^2 & a = b \\
2 f_a f_b & a \neq b
\end{cases}
$$

With $n$ alleles that is $n(n+1)/2$ unordered pairs — the triangular number the
build already uses to check the diplotype table is complete.

An assay is a deterministic many-to-one map. Masking under panel $S$ replaces
every allele outside $S$ with `*1`, so the likelihood is an indicator, not a
density:

$$
P(o \mid a/b,\, S) = \mathbf{1}\!\left[\, m_S(a/b) = o \,\right]
$$

Bayes therefore reduces to conditioning on a set: the posterior is the prior
restricted to the preimage of the observation, renormalised.

$$
P(a/b \mid o,\, S) = \frac{P(a/b)\;\mathbf{1}\!\left[\, m_S(a/b) = o \,\right]}
{\displaystyle\sum_{c/d \,\in\, m_S^{-1}(o)} P(c/d)}
$$

Phenotype is itself a deterministic map $g$, so the phenotype posterior is a
pushforward — the diplotype posterior summed over each fibre of $g$:

$$
P(k \mid o,\, S) = \sum_{a/b \,\in\, g^{-1}(k)} P(a/b \mid o,\, S)
$$

Two limits fall out of this and are asserted as tests. With $S = \varnothing$
every diplotype masks to `*1/*1`, the preimage $m_S^{-1}(o)$ is everything, and
the posterior is the prior marginal. With $S = A$ the mask is the identity, the
preimage is a singleton, and the posterior is a point mass on the truth.

### Scoring

Brier and log loss are both *strictly proper*: expected score is minimised by
reporting your actual beliefs, so a forecast cannot win by hedging. Brier is
the headline because it is bounded.

$$
\mathrm{BS}(p,\, k) = \sum_{j} \left( p_j - \mathbf{1}\!\left[\, j = k \,\right] \right)^{2}
\qquad \mathrm{BS} \in [0,\, 2]
$$

A confidently wrong point call costs 2.0. The same call costs log loss
infinity, which is why that metric is floored — and why, once floored, it is
largely counting misses.

Propriety carries one consequence worth stating. Under a strictly proper rule,
a correctly specified prior makes the posterior the Bayes-optimal forecast, and
no other predictor — the point call included — can beat it in expectation. So
CYP2B6 scoring *worse* than the point call is evidence that the prior is
misspecified for this cohort, not that the method fails. GeT-RM was assembled
to be rich in variant alleles and is not drawn from the frequencies the prior
describes.

### Limitations / What is not learned

Nothing here is fitted: 652 samples against 16,836 CYP2D6 diplotypes 
leaves nothing to fit that would not be fitting noise. The prior is 
CPIC's published allele frequencies, the likelihood is the definition of 
the assay, and no parameter is estimated from data.

A panel induces a partition of diplotype space; a narrower panel is a coarser
partition; the Brier gain tracks how much phenotype uncertainty survives the
observation. It is largest where the partition is coarsest and vanishes when
the mask becomes injective.

## Sources and licences

- Gene–drug recommendations, diplotype–phenotype tables and allele functions:
  [CPIC / ClinPGx](https://cpicpgx.org), retrieved through their public REST API.
  Guideline text is reproduced verbatim and attributed in every report.
- Reference diplotypes: [CDC GeT-RM](https://www.cdc.gov/labquality/get-rm.html).
- Typefaces: Cascadia Mono and Courier Prime, both SIL OFL 1.1 — see
  `client/src/main/resources/fonts/NOTICE.txt`.

## AI Disclosure

This project was built with assistance from Claude (Anthropic),
used as a pair programmer with Copilot and live agent coding,
and a writing assistant (assisting in populating this README.md 
from drafted MS) across sessions between October and November 2026. 

Responsibility for the correctness of what is here is mine.
