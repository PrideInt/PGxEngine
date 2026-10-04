import argparse
import json
import sqlite3
import sys
from datetime import datetime, timezone

import requests

API_URL = "https://api.cpicpgx.org/v1"
PAGE_SIZE = 1000

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);

CREATE TABLE IF NOT EXISTS diplotype (
    gene           TEXT NOT NULL,
    diplotype      TEXT NOT NULL,
    phenotype      TEXT,
    activity_score TEXT,
    lookupkey      TEXT,
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

CREATE INDEX IF NOT EXISTS idx_rec_drug ON recommendation(drug_name);
CREATE INDEX IF NOT EXISTS idx_pair_drug ON pair(drug_id);
"""

RECOMMENDATION_TEXT_COLUMN = "drugrecommendation"
RECOMMENDATION_CLASS_COLUMN = "classification"

# Fetch through a PostgREST endpoint
def fetch_all(endpoint, **args):
    rows = []
    offset = 0

    while True:
        query = dict(args, limit=str(PAGE_SIZE), offset=str(offset))
        response = requests.get(f"{API_URL}/{endpoint}", params=query, timeout=60)

        response.raise_for_status()
        batch = response.json()

        if not batch:
            return rows

        rows.extend(batch)
        offset += len(batch)

# Normalize API values for storage
def flatten(value):
    if value in (None, "", {}, []):
        return None
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return "; ".join(f"{key}: {item}" for key, item in value.items())
    if isinstance(value, list):
        return "; ".join(str(item) for item in value)
    
    return str(value)

def as_json(value):
    if value in (None, "", {}, []):
        return None
    
    return json.dumps(value, sort_keys=True)

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
def build(database_path, genes):
    connection = sqlite3.connect(database_path)
    connection.executescript(SCHEMA)
    connection.execute("DELETE FROM pair")

    # Retrieve guideline URLs and psychotropic drug names
    guideline_urls = {
        guideline["id"]: guideline.get("url")
        for guideline in fetch_all("guideline")
    }
    drugs = {drug["drugid"]: drug for drug in fetch_all("drug")}

    # Store diplotype to phenotype mappings
    diplotype_count = 0

    for gene in genes:
        rows = fetch_all("diplotype", genesymbol=f"eq.{gene}")

        for row in rows:
            lookup_key = row.get("lookupkey")

            connection.execute(
                "INSERT OR REPLACE INTO diplotype VALUES (?,?,?,?,?)",
                (
                    row.get("genesymbol"),
                    row.get("diplotype"),
                    row.get("generesult"),
                    extract_activity_score(gene, lookup_key),
                    as_json(lookup_key)
                )
            )
            diplotype_count += 1

        if not rows:
            print(
                f"  {gene}: NO DIPLOTYPES RETURNED. The gene may have no "
                "standardized genotype to phenotype mapping.",
                file=sys.stderr
            )
        else:
            print(f"  {gene}: {len(rows)} diplotypes", file=sys.stderr)

        print(f"  {gene}: {len(rows)} diplotypes", file=sys.stderr)

    # Store therapeutic recommendations
    recommendation_count = 0

    for row in fetch_all("recommendation"):
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
                row.get("comments"),
                row.get("population"),
                guideline_urls.get(row.get("guidelineid"))
            )
        )
        recommendation_count += 1

    # Store gene and drug pairs with their CPIC levels
    for row in fetch_all("pair"):
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

    # Record provenance
    retrieved_at = datetime.now(timezone.utc).isoformat(timespec="seconds")

    connection.executemany(
        "INSERT OR REPLACE INTO meta VALUES (?,?)",
        [
            ("retrieved_at", retrieved_at),
            ("genes", ",".join(genes)),
            ("source", API_URL),
            ("cpic_db", "UNPINNED"),
            ("pharmvar", "UNPINNED")
        ]
    )
    connection.commit()

    missing_text = connection.execute("SELECT COUNT(*) FROM recommendation WHERE drug_recommendation IS NULL").fetchone()[0]
    missing_lookup = connection.execute("SELECT COUNT(*) FROM recommendation WHERE lookupkey IS NULL").fetchone()[0]
    missing_drug = connection.execute("SELECT COUNT(*) FROM recommendation WHERE drug_name IS NULL").fetchone()[0]

    print(
        f"Wrote {database_path}: {diplotype_count} diplotypes, "
        f"{recommendation_count} recommendations",
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
        build(arguments.out, [gene.upper() for gene in arguments.genes])
