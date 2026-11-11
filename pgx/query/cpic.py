import argparse
import json
import sqlite3
import sys
from datetime import datetime, timezone

import requests

from ..models import KB_SCHEMA

API_URL = "https://api.cpicpgx.org/v1"
PAGE_SIZE = 1000

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);

CREATE TABLE IF NOT EXISTS diplotype (
    gene                 TEXT NOT NULL,
    diplotype            TEXT NOT NULL,
    phenotype            TEXT,
    activity_score       TEXT,
    total_activity_score TEXT,
    function_1           TEXT,
    function_2           TEXT,
    ehr_priority         TEXT,
    description          TEXT,
    consultation_text    TEXT,
    lookupkey            TEXT,
    PRIMARY KEY (gene, diplotype)
);

CREATE TABLE IF NOT EXISTS recommendation (
    rec_id              TEXT PRIMARY KEY,
    drug_id             TEXT,
    drug_name           TEXT,
    lookupkey           TEXT,
    classification      TEXT,
    drug_recommendation TEXT,
    implications        TEXT,
    comments            TEXT,
    population          TEXT,
    guideline_url       TEXT
);

CREATE TABLE IF NOT EXISTS pair (
    gene       TEXT,
    drug_id    TEXT,
    drug_name  TEXT,
    cpic_level TEXT
);

CREATE TABLE IF NOT EXISTS allele (
    gene               TEXT NOT NULL,
    allele             TEXT NOT NULL,
    function           TEXT,
    clinical_function  TEXT,
    activity_value     TEXT,
    inferred_frequency INTEGER,
    strength           TEXT,
    PRIMARY KEY (gene, allele)
);

-- One row per observed frequency. A group CPIC has no data for gets NO ROW,
-- so absent reads as absent; a stored 0.0 is an observed zero. Collapsing the
-- two would push the missing mass onto *1 and bias the prior hardest in the
-- populations CPIC has characterised least.
CREATE TABLE IF NOT EXISTS allele_frequency (
    gene       TEXT NOT NULL,
    allele     TEXT NOT NULL,
    population TEXT NOT NULL,
    frequency  REAL,
    PRIMARY KEY (gene, allele, population)
);

CREATE INDEX IF NOT EXISTS idx_freq_gene ON allele_frequency(gene, population);
CREATE INDEX IF NOT EXISTS idx_rec_drug ON recommendation(drug_name);
CREATE INDEX IF NOT EXISTS idx_pair_drug ON pair(drug_id);
"""

RECOMMENDATION_TEXT_COLUMN = "drugrecommendation"
RECOMMENDATION_CLASS_COLUMN = "classification"

# Fetch through a PostgREST endpoint
def fetch_all(endpoint, order=None, **args):
    rows = []
    offset = 0

    while True:
        query = dict(args, limit=str(PAGE_SIZE), offset=str(offset))

        if order:
            query["order"] = order

        response = requests.get(f"{API_URL}/{endpoint}", params=query, timeout=60)

        response.raise_for_status()
        batch = response.json()

        if not batch:
            return rows

        rows.extend(batch)
        offset += len(batch)

# Normalize API values for storage
def normalize_text(value):
    if value is None:
        return None
 
    text = str(value).strip()
 
    if text.lower() in ("", "n/a", "na", "none"):
        return None
 
    return text

# CPIC publishes two spellings of the normal priority band, one with a stray
# space after a slash ("Normal/Routine/ Low Risk"), and the spaced form is the
# more common of the two. Collapse them so a reader grouping on this string
# sees one band and not two.
def normalize_priority(value):
    text = normalize_text(value)

    if text is None:
        return None

    return "/".join(part.strip() for part in text.split("/"))

def flatten(value):
    if value in (None, "", {}, []):
        return None
 
    if isinstance(value, str):
        return normalize_text(value)
 
    if isinstance(value, dict):
        parts = [
            f"{key}: {normalize_text(item)}" for key, item in value.items() if normalize_text(item) is not None
        ]
        return "; ".join(parts) or None
 
    if isinstance(value, list):
        return "; ".join(str(item) for item in value)
 
    return str(value)

def as_json(value):
    if value in (None, "", {}, []):
        return None

    return json.dumps(value, sort_keys=True, ensure_ascii=False)

# Print live column names before trusting the column mapping
def inspect_endpoint(endpoint):
    response = requests.get(
        f"{API_URL}/{endpoint}",
        params={"limit": "1"},
        timeout=30
    )
    response.raise_for_status()
    rows = response.json()

    if not rows:
        print(f"{endpoint}: no rows returned")
        return

    print(f"--- {endpoint} ---")
    for column, value in rows[0].items():
        preview = json.dumps(value)[:70]
        print(f"  {column:<24} {preview}")

def extract_activity_score(gene, lookup_key):
    if not isinstance(lookup_key, dict):
        return None

    value = lookup_key.get(gene)

    if value is None or value == "n/a":
        return None

    text = str(value)

    if text.lstrip("\u2265").replace(".", "", 1).isdigit():
        return text

    return None

# Build the knowledge base
def store_alleles(connection, genes):
    """Allele function and biogeographic-group frequencies.

    CPIC publishes the aggregated frequency on the allele record itself, as a
    JSON object keyed by group. The allele_frequency and population endpoints
    hold the study-level rows underneath it; aggregating those across cohorts
    of 61 subjects each is CPIC's job, and they have done it.
    """
    allele_count = 0
    frequency_count = 0

    for gene in genes:
        rows = fetch_all("allele", order="name.asc", genesymbol=f"eq.{gene}")

        if not rows:
            print(f"  {gene}: no alleles returned", file=sys.stderr)
            continue

        for row in rows:
            connection.execute(
                "INSERT OR REPLACE INTO allele ("
                "  gene, allele, function, clinical_function, activity_value,"
                "  inferred_frequency, strength"
                ") VALUES (?,?,?,?,?,?,?)",
                (
                    row.get("genesymbol"),
                    row.get("name"),
                    normalize_text(row.get("functionalstatus")),
                    normalize_text(row.get("clinicalfunctionalstatus")),
                    normalize_text(row.get("activityvalue")),
                    1 if row.get("inferredfrequency") else 0,
                    normalize_text(row.get("strength"))
                )
            )
            allele_count += 1

            frequencies = row.get("frequency")

            if not isinstance(frequencies, dict):
                continue

            for population, value in frequencies.items():
                if value is None:
                    continue

                connection.execute(
                    "INSERT OR REPLACE INTO allele_frequency VALUES (?,?,?,?)",
                    (row.get("genesymbol"), row.get("name"), population, float(value))
                )
                frequency_count += 1

    return allele_count, frequency_count


def report_frequency_coverage(connection, genes):
    """Within a population the allele frequencies should sum to about 1.

    The same kind of check as the triangular number on diplotype counts. A sum
    well short of 1 means either that alleles are missing from this build or
    that CPIC has only partly characterised that group; either way a
    Hardy-Weinberg prior built on it would quietly hand the shortfall to *1,
    which is the exact assumption this is meant to interrogate.
    """
    for gene in genes:
        rows = connection.execute(
            "SELECT population, COUNT(*), SUM(frequency) FROM allele_frequency "
            "WHERE gene = ? GROUP BY population ORDER BY SUM(frequency) DESC",
            (gene,)
        ).fetchall()

        if not rows:
            print(f"  {gene}: no frequencies stored", file=sys.stderr)
            continue

        complete = [row for row in rows if 0.99 <= (row[2] or 0) <= 1.01]
        print(
            f"  {gene}: {len(rows)} populations, "
            f"{len(complete)} summing to 1.00 +/- 0.01",
            file=sys.stderr
        )

        for population, alleles, total in rows:
            if not (0.99 <= (total or 0) <= 1.01):
                print(
                    f"      {population}: {alleles} alleles, sum {(total or 0):.4f}",
                    file=sys.stderr
                )


def build(database_path, genes, cpic_version="UNPINNED", pharmvar_version="UNPINNED"):
    connection = sqlite3.connect(database_path)

    # A build produces a fresh knowledge base. Dropping first means CREATE TABLE
    # IF NOT EXISTS can never silently keep an older table's columns, and no row
    # from a previous build can survive into this one.
    for table in (
        "diplotype", "recommendation", "pair", "allele", "allele_frequency", "meta"
    ):
        connection.execute(f"DROP TABLE IF EXISTS {table}")

    connection.executescript(SCHEMA)

    # Retrieve guideline URLs and psychotropic drug names
    guideline_urls = {
        guideline["id"]: guideline.get("url") for guideline in fetch_all("guideline", order="id.asc")
    }
    drugs = {
        drug["drugid"]: drug for drug in fetch_all("drug", order="drugid.asc")
    }

    # Store diplotype to phenotype mappings
    diplotype_count = 0

    for gene in genes:
        rows = fetch_all("diplotype", order="diplotype.asc", genesymbol=f"eq.{gene}")

        for row in rows:
            lookup_key = row.get("lookupkey")

            connection.execute(
                "INSERT OR REPLACE INTO diplotype ("
                "  gene, diplotype, phenotype, activity_score, total_activity_score,"
                "  function_1, function_2, ehr_priority, description,"
                "  consultation_text, lookupkey"
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (
                    row.get("genesymbol"),
                    row.get("diplotype"),
                    row.get("generesult"),
                    extract_activity_score(gene, lookup_key),
                    normalize_text(row.get("totalactivityscore")),
                    normalize_text(row.get("function1")),
                    normalize_text(row.get("function2")),
                    normalize_priority(row.get("ehrpriority")),
                    normalize_text(row.get("description")),
                    normalize_text(row.get("consultationtext")),
                    as_json(lookup_key)
                )
            )
            diplotype_count += 1

        if not rows:
            print(f"  {gene}: NO DIPLOTYPES RETURNED. The gene may have no standardized genotype to phenotype mapping.", file=sys.stderr)

    # Store therapeutic recommendations
    recommendation_count = 0

    for row in fetch_all("recommendation", order="id.asc"):
        drug_id = row.get("drugid")
        drug = drugs.get(drug_id, {})

        connection.execute(
            "INSERT OR REPLACE INTO recommendation VALUES (?,?,?,?,?,?,?,?,?,?)",
            (
                str(row.get("id")),
                drug_id,
                drug.get("name"),
                as_json(row.get("lookupkey")),
                row.get(RECOMMENDATION_CLASS_COLUMN),
                row.get(RECOMMENDATION_TEXT_COLUMN),
                flatten(row.get("implications")),
                normalize_text(row.get("comments")),
                row.get("population"),
                guideline_urls.get(row.get("guidelineid"))
            )
        )
        recommendation_count += 1

    # Store gene and drug pairs with their CPIC levels
    for row in fetch_all("pair", order="drugid.asc,genesymbol.asc"):
        drug_id = row.get("drugid")

        connection.execute(
            "INSERT INTO pair VALUES (?,?,?,?)",
            (
                row.get("genesymbol"),
                drug_id,
                drugs.get(drug_id, {}).get("name"),
                row.get("cpiclevel")
            )
        )

    # Allele function and frequency, for the panel and prior work
    allele_count, frequency_count = store_alleles(connection, genes)

    # Record provenance
    retrieved_at = datetime.now(timezone.utc).isoformat(timespec="seconds")

    connection.executemany(
        "INSERT OR REPLACE INTO meta VALUES (?,?)",
        [
            ("retrieved_at", retrieved_at),
            ("genes", ",".join(genes)),
            ("source", API_URL),
            ("kb_schema", KB_SCHEMA),
            ("cpic_db", cpic_version),
            ("pharmvar", pharmvar_version)
        ]
    )
    connection.commit()

    # Verify table
    for gene in genes:
        stored = connection.execute(
            "SELECT COUNT(*) FROM diplotype WHERE gene = ?",
            (gene,)
        ).fetchone()[0]

        alleles = int((((8 * stored + 1) ** 0.5) - 1) / 2 + 0.5)

        if alleles * (alleles + 1) // 2 == stored:
            print(f"  {gene}: {stored} stored, {alleles} alleles", file=sys.stderr)
        else:
            print(f"  {gene}: {stored} stored, NOT a complete allele pair set", file=sys.stderr)

    report_frequency_coverage(connection, genes)

    missing_text = connection.execute("SELECT COUNT(*) FROM recommendation WHERE drug_recommendation IS NULL").fetchone()[0]
    missing_lookup = connection.execute("SELECT COUNT(*) FROM recommendation WHERE lookupkey IS NULL").fetchone()[0]
    missing_drug = connection.execute("SELECT COUNT(*) FROM recommendation WHERE drug_name IS NULL").fetchone()[0]

    print(
        f"Wrote {database_path}: {diplotype_count} diplotypes, "
        f"{recommendation_count} recommendations, "
        f"{allele_count} alleles, {frequency_count} frequencies",
        file=sys.stderr
    )
    if missing_text or missing_lookup or missing_drug:
        print(
            f"Missing data in {database_path}: "
            f"{missing_text} missing drug_recommendation, "
            f"{missing_lookup} missing lookupkey, "
            f"{missing_drug} missing drug_name. ",
            "Check column names with --inspect",
            file=sys.stderr
        )

# The CLI stuff
parser = argparse.ArgumentParser(description="Build the local CPIC knowledge base")
parser.add_argument("--out", default="pgx.db")
parser.add_argument(
    "--genes",
    nargs="+",
    default=["CYP2C19", "CYP2D6", "CYP2B6", "SLC6A4", "HTR2A"]
)
parser.add_argument("--cpic-version", default="UNPINNED", help="CPIC data release tag, from github.com/cpicpgx/cpic-data/releases")
parser.add_argument("--pharmvar-version", default="UNPINNED", help="PharmVar version, from pharmvar.org/download")
parser.add_argument(
    "--inspect",
    nargs="*",
    metavar="ENDPOINT",
    help="Print live column names instead of building the database"
)

if __name__ == "__main__":
    arguments = parser.parse_args()

    if arguments.inspect is not None:
        endpoints = arguments.inspect or [
            "diplotype",
            "recommendation",
            "drug",
            "pair"
        ]
        for endpoint in endpoints:
            inspect_endpoint(endpoint)
    else:
        build(arguments.out, [gene.upper() for gene in arguments.genes], arguments.cpic_version, arguments.pharmvar_version)
