import argparse
import csv
import json
import sys

from .core.engine import generate_report, open_database

# Parse repeated GENE=DIPLOTYPE arguments
def parse_gene_arguments(pairs):
    diplotypes = {}
    for pair in pairs:
        if "=" not in pair:
            raise ValueError(f"Malformed gene argument {pair!r}. Expected GENE=DIPLOTYPE.")
        
        gene, diplotype = pair.split("=", 1)
        diplotypes[gene.strip().upper()] = diplotype.strip()

    return diplotypes

# Run many samples from a CSV, one JSON report per line
def run_batch(connection, csv_path, drugs):
    with open(csv_path, newline="") as handle:
        reader = csv.DictReader(handle)
        
        if reader.fieldnames is None:
            raise ValueError(f"No header row found in {csv_path!r}.")

        gene_columns = [
            column for column in reader.fieldnames if column.lower() != "sample"
        ]

        for row in reader:
            diplotypes = { column.upper(): row[column].strip() for column in gene_columns if row.get(column) and row[column].strip() }

            report = generate_report(connection, diplotypes, drugs)
            report["sample"] = row.get("sample")

            print(json.dumps(report))

# Command line entry
parser = argparse.ArgumentParser(
    description="Pharmacogenomic guideline lookup, educational use only"
)
parser.add_argument("--db", default="pgx.db")
parser.add_argument(
    "--gene",
    action="append",
    default=[],
    metavar="GENE=DIPLOTYPE"
)
parser.add_argument("--drug", action="append", default=[])
parser.add_argument("--batch", metavar="CSV")
parser.add_argument("--all-matches", action="store_true")
parser.add_argument("--pretty", action="store_true")

if __name__ == "__main__":
    arguments = parser.parse_args()

    connection = open_database(arguments.db)
    drugs = arguments.drug or None

    if arguments.batch:
        run_batch(connection, arguments.batch, drugs)
    elif not arguments.gene:
        parser.error("Provide at least one --gene, or use --batch.")
    else:
        report = generate_report(
            connection,
            parse_gene_arguments(arguments.gene),
            drugs,
            specific_only=not arguments.all_matches
        )
        json.dump(report, sys.stdout, indent=2 if arguments.pretty else None)
        sys.stdout.write("\n")
