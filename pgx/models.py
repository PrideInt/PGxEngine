SCHEMA_VERSION = "1.0"
ENGINE_VERSION = "0.1.0"

DISCLAIMER = (
    "Educational use only. Not clinical decision support. "
    "Not validated for patient care."
)

# Build one gene result
def gene_result(gene, diplotype, phenotype=None, activity_score=None, lookup_key=None, error=None):
    return {
        "gene": gene,
        "diplotype": diplotype,
        "phenotype": phenotype,
        "activity_score": activity_score,
        "lookup_key": lookup_key or {},
        "error": error
    }

def is_resolved(result):
    return result["error"] is None and result["phenotype"] is not None

# Build one drug recommendation
def drug_recommendation(drug, genes, classification, recommendation, implications=None, comments=None, population=None, cpic_level=None, guideline_url=None, source="CPIC"):
    return {
        "drug": drug,
        "genes": genes,
        "classification": classification,
        "recommendation": recommendation,
        "implications": implications,
        "comments": comments,
        "population": population,
        "source": source,
        "cpic_level": cpic_level,
        "guideline_url": guideline_url
    }

# Assemble provenance and the full report
def source_versions(cpic_db=None, pharmvar=None, retrieved_at=None):
    return {
        "cpic_db": cpic_db,
        "pharmvar": pharmvar,
        "retrieved_at": retrieved_at
    }

def report(genes, drugs, sources, warnings=None):
    return {
        "engine_version": ENGINE_VERSION,
        "schema_version": SCHEMA_VERSION,
        "sources": sources,
        "genes": genes,
        "drugs": drugs,
        "warnings": warnings or [],
        "disclaimer": DISCLAIMER
    }
