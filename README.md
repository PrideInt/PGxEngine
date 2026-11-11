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
client/            JavaFX client (Java 17, JavaFX 21)
packaging/         build scripts and PyInstaller spec
docs/scope.md      scope, method, findings, limitations
tests/             99 tests, offline against a fixture database
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
phenotype and no recommendation for the six drugs in scope. Method and full
results are in [`docs/scope.md`](docs/scope.md).

## Sources and licences

- Gene–drug recommendations, diplotype–phenotype tables and allele functions:
  [CPIC / ClinPGx](https://cpicpgx.org), retrieved through their public REST API.
  Guideline text is reproduced verbatim and attributed in every report.
- Reference diplotypes: [CDC GeT-RM](https://www.cdc.gov/labquality/get-rm.html).
- Typefaces: Cascadia Mono and Courier Prime, both SIL OFL 1.1 — see
  `client/src/main/resources/fonts/NOTICE.txt`.
