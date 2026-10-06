import json
import re
import sqlite3

from ..models import (
    drug_recommendation,
    gene_result,
    is_resolved,
    report,
    source_versions
)

allele_separator = re.compile(r"\s*/\s*")
star_number = re.compile(r"^\*(\d+)")

# Open the knowledge base read-only
def open_database(path):
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    
    return connection

# Normalize diplotype strings for lookup
def allele_sort_key(allele):
    match = star_number.match(allele)

    if match:
        return (0, int(match.group(1)), allele)
    
    return (1, 0, allele)

def normalize_diplotype(diplotype):
    collapsed = diplotype.strip().replace(" ", "")
    alleles = [allele for allele in allele_separator.split(collapsed) if allele]

    if len(alleles) != 2:
        raise ValueError(f"Expected two alleles separated by a slash, received {diplotype!r}.")
    
    alleles.sort(key=allele_sort_key)

    return "/".join(alleles)

# Resolve a diplotype to a phenotype
def lookup_gene(connection, gene, diplotype):
    gene = gene.strip().upper()

    try:
        normalized = normalize_diplotype(diplotype)
    except ValueError as error:
        return gene_result(gene, diplotype, error=str(error))

    row = connection.execute(
        "SELECT phenotype, activity_score, lookupkey FROM diplotype "
        "WHERE gene = ? AND diplotype = ?",
        (gene, normalized)
    ).fetchone()

    if row is None:
        row = connection.execute(
            "SELECT phenotype, activity_score, lookupkey FROM diplotype "
            "WHERE gene = ? AND diplotype = ?",
            (gene, diplotype.strip())
        ).fetchone()

    if row is None:
        gene_is_known = connection.execute(
            "SELECT 1 FROM diplotype WHERE gene = ? LIMIT 1",
            (gene,)
        ).fetchone()

        if gene_is_known:
            reason = f"Diplotype {normalized!r} not found for {gene}."
        else:
            reason = f"Gene {gene!r} is not present in this knowledge base."
        return gene_result(gene, normalized, error=reason)

    return gene_result(
        gene,
        normalized,
        phenotype=row["phenotype"],
        activity_score=row["activity_score"],
        lookup_key=json.loads(row["lookupkey"]) if row["lookupkey"] else {}
    )

# Match resolved phenotypes against guideline recommendations
def cpic_levels(connection):
    rows = connection.execute(
        "SELECT gene, drug_name, cpic_level FROM pair "
        "WHERE drug_name IS NOT NULL AND cpic_level IS NOT NULL"
    )

    return { (row["gene"], row["drug_name"].lower()): row["cpic_level"] for row in rows }

def strongest_level(levels, drug, genes):
    found = [
        levels[(gene, drug.lower())]
        for gene in genes
        if (gene, drug.lower()) in levels
    ]
    if not found:
        return None
    
    return min(found)

def merged_lookup_key(results):
    merged = {}

    for result in results:
        if is_resolved(result):
            merged.update(result["lookup_key"])

    return merged

def most_specific_recommendations(recommendations):
    highest_gene_count = {}

    for recommendation in recommendations:
        key = (recommendation["drug"].lower(), recommendation["population"] or "")
        gene_count = len(recommendation["genes"])
        highest_gene_count[key] = max(highest_gene_count.get(key, 0), gene_count)

    return [
        recommendation for recommendation in recommendations if len(recommendation["genes"]) == highest_gene_count[
            (recommendation["drug"].lower(), recommendation["population"] or "")
        ]
    ]

def find_recommendations(connection, results, drugs=None, specific_only=True):
    patient_key = merged_lookup_key(results)

    if not patient_key:
        return []

    levels = cpic_levels(connection)

    query = (
        "SELECT r.drug_name, r.lookupkey, r.classification, r.drug_recommendation, "
        "       r.implications, r.comments, r.population, r.guideline_url "
        "FROM recommendation r"
    )
    parameters = []

    if drugs:
        placeholders = ",".join("?" for drug in drugs)
        query += f" WHERE LOWER(r.drug_name) IN ({placeholders})"
        parameters = [drug.lower() for drug in drugs]

    matches = []

    for row in connection.execute(query, parameters):
        required = json.loads(row["lookupkey"]) if row["lookupkey"] else { }

        if not required:
            continue

        if all(patient_key.get(gene) == value for gene, value in required.items()):
            matches.append(
                drug_recommendation(
                    drug=row["drug_name"],
                    genes=sorted(required),
                    classification=row["classification"],
                    recommendation=row["drug_recommendation"],
                    implications=row["implications"],
                    comments=row["comments"],
                    population=row["population"],
                    cpic_level=strongest_level(
                        levels,
                        row["drug_name"],
                        sorted(required)
                    ),
                    guideline_url=row["guideline_url"]
                )
            )

    if specific_only:
        matches = most_specific_recommendations(matches)

    matches.sort(key=lambda match: (match["drug"].lower(), match["population"] or ""))

    return matches

# Assemble the report
def stored_source_versions(connection):
    stored = { row["key"]: row["value"] for row in connection.execute("SELECT key, value FROM meta") }

    return source_versions(
        cpic_db=stored.get("cpic_db"),
        pharmvar=stored.get("pharmvar"),
        retrieved_at=stored.get("retrieved_at")
    )

def generate_report(connection, diplotypes, drugs=None, specific_only=True):
    results = [
        lookup_gene(connection, gene, diplotype) for gene, diplotype in diplotypes.items()
    ]
    recommendations = find_recommendations(connection, results, drugs, specific_only)

    warnings = [
        f"{result['gene']}: {result['error']}" for result in results if result["error"]
    ]

    if drugs:
        matched = { recommendation["drug"].lower() for recommendation in recommendations }
        
        for requested in drugs:
            if requested.lower() not in matched:
                warnings.append(
                    f"No applicable recommendation for {requested!r}. The drug is "
                    "absent from the knowledge base, or a required gene was not typed."
                )

    return report(results, recommendations, stored_source_versions(connection), warnings)
