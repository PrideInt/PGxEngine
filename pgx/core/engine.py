import json
import re
import sqlite3

from ..models import (
    KB_SCHEMA,
    drug_recommendation,
    gene_result,
    is_resolved,
    report,
    source_versions
)

NO_RESULT = "No Result"
 
allele_separator = re.compile(r"\s*/\s*")
star_number = re.compile(r"^\*(\d+)")

# Open the knowledge base read-only
def open_database(path):
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row

    row = connection.execute("SELECT value FROM meta WHERE key = 'kb_schema'").fetchone()
    stored = row["value"] if row else None

    if stored != KB_SCHEMA:
        raise ValueError(
            f"The knowledge base at {path!r} was built for schema {stored or 'pre-1.1'}, but this engine expects {KB_SCHEMA}. "
            "Rebuild it with: python -m pgx.query.cpic --genes CYP2C19 CYP2D6 CYP2B6 --out data/pgx.db"
        )

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
        "SELECT phenotype, activity_score, ehr_priority, consultation_text, function_1, function_2, lookupkey FROM diplotype "
        "WHERE gene = ? AND diplotype = ?",
        (gene, normalized)
    ).fetchone()

    if row is None:
        row = connection.execute(
            "SELECT phenotype, activity_score, ehr_priority, consultation_text, function_1, function_2, lookupkey FROM diplotype "
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
        ehr_priority=row["ehr_priority"],
        consultation_text=row["consultation_text"],
        allele_functions=[f for f in (row["function_1"], row["function_2"]) if f],
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

        if all(patient_key.get(gene, NO_RESULT) == value for gene, value in required.items()):
            matches.append(
                drug_recommendation(
                    drug=row["drug_name"],
                    genes=sorted(gene for gene, value in required.items() if value != NO_RESULT),
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
# Browse the knowledge base
def escape_like(text):
    for character in ("\\", "%", "_"):
        text = text.replace(character, "\\" + character)

    return text


def search_diplotypes(connection, gene, query=None, limit=50):
    """Diplotypes for one gene, narrowed by a substring so a picker can use them.

    CYP2D6 alone holds 16,836, so this never returns a whole gene unasked.
    """
    gene = gene.strip().upper()
    where = "gene = ?"
    parameters = [gene]

    if query:
        where += " AND diplotype LIKE ? ESCAPE '\\'"
        parameters.append(f"%{escape_like(query.strip())}%")

    total = connection.execute(
        f"SELECT COUNT(*) AS total FROM diplotype WHERE {where}", parameters
    ).fetchone()["total"]

    rows = connection.execute(
        "SELECT diplotype, phenotype, activity_score, ehr_priority FROM diplotype "
        f"WHERE {where} ORDER BY LENGTH(diplotype), diplotype LIMIT ?",
        parameters + [limit]
    ).fetchall()

    return {
        "gene": gene,
        "query": query or None,
        "total": total,
        "limit": limit,
        "diplotypes": [dict(row) for row in rows]
    }


def list_drugs(connection, answerable_only=True):
    """Drugs the knowledge base holds recommendations for.

    A drug is answerable when at least one of its recommendations keys only on
    genes this build loaded. Reporting that, rather than every drug CPIC
    covers, is what lets an interface populate a list it can actually serve.
    """
    loaded = {
        row["gene"] for row in connection.execute("SELECT DISTINCT gene FROM diplotype")
    }
    levels = cpic_levels(connection)
    found = {}

    for row in connection.execute(
        "SELECT drug_name, lookupkey FROM recommendation WHERE drug_name IS NOT NULL"
    ):
        required = json.loads(row["lookupkey"]) if row["lookupkey"] else {}
        combination = frozenset(
            gene for gene, value in required.items() if value != NO_RESULT
        )

        entry = found.setdefault(row["drug_name"], {"combinations": set(), "count": 0})
        entry["combinations"].add(combination)
        entry["count"] += 1

    drugs = []

    for drug, entry in found.items():
        servable = [
            combination for combination in entry["combinations"]
            if combination and combination <= loaded
        ]
        answerable = bool(servable)
        genes = sorted(set().union(*(servable or entry["combinations"] or [set()])))

        if answerable_only and not answerable:
            continue

        drugs.append({
            "drug": drug,
            "genes": genes,
            "cpic_level": strongest_level(levels, drug, genes),
            "recommendation_count": entry["count"],
            "answerable": answerable
        })

    drugs.sort(key=lambda drug: drug["drug"].lower())

    return drugs


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
