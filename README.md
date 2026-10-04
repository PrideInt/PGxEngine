# An Interpretable Pharmacogenomics Psychotropic Metabolizer Engine

> BMI 530: Software Development in Biomedical Informatics

A pharmacogenomics prototype engine that demonstrates how a reported star-allele diplotype can be 
mapped to a metabolizer phenotype and matched to public gene–drug recommendations. 
This prototype supports CYP2C19 and CYP2D6 as example pharmacogenes,
multiple psychotropics across different classes and demonstrates how their diplotypes
can be translated into metabolizer phenotypes.

> [!IMPORTANT]
> *Educational prototype — not for clinical use or medical decisions.*

## Running the Engine

To run the engine, you need to have Python 3.11 or higher installed along with the required dependencies listed below.

### Accessing CPIC Data

```bash
# Check live column names first
python -m pgx.query.cpic --inspect

python -m pgx.query.cpic --genes CYP2C19 CYP2D6 CYP2B6 SLC6A4 HTR2A --out pgx.db
```

## Requirements

### Python 3.11 or higher

### FastAPI >= 0.110

```bash
pip install fastapi
```

### Uvicorn >= 0.27

```bash
pip install uvicorn[standard]
```

### Pydantic >= 2.6

```bash
pip install pydantic
```

### Requests >= 2.31

```bash
pip install requests
```