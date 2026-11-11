import os
import sqlite3
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel

from ..core.engine import generate_report, list_drugs, search_diplotypes

DATABASE_PATH = os.environ.get("PGX_DB", "pgx.db")

state = {}


# Hold one read-only connection for the lifetime of the service
@asynccontextmanager
async def lifespan(app):
    if not os.path.exists(DATABASE_PATH):
        raise ValueError(
            f"Knowledge base {DATABASE_PATH!r} not found. Build it first with "
            "python -m pgx.query.cpic --genes CYP2C19 CYP2D6 CYP2B6 --out data/pgx.db"
        )

    connection = sqlite3.connect(
        f"file:{DATABASE_PATH}?mode=ro",
        uri=True,
        check_same_thread=False
    )
    connection.row_factory = sqlite3.Row
    state["connection"] = connection
    yield
    connection.close()


app = FastAPI(
    title="PGx Engine",
    version="0.1.0",
    description="Educational pharmacogenomic guideline lookup. Not clinical software.",
    lifespan=lifespan
)


# Request body
class ReportRequest(BaseModel):
    diplotypes: dict
    drugs: list | None = None
    specific_only: bool = True


# Service endpoints
@app.get("/health")
def health():
    connection = state["connection"]
    stored = {
        row["key"]: row["value"]
        for row in connection.execute("SELECT key, value FROM meta")
    }
    loaded = connection.execute(
        "SELECT COUNT(*) AS total FROM diplotype"
    ).fetchone()["total"]
    return {
        "status": "ok",
        "diplotypes_loaded": loaded,
        "sources": stored
    }


@app.get("/genes")
def genes():
    connection = state["connection"]
    rows = connection.execute(
        "SELECT gene, COUNT(*) AS total FROM diplotype GROUP BY gene ORDER BY gene"
    )
    return {
        "genes": [
            {"gene": row["gene"], "diplotype_count": row["total"]}
            for row in rows
        ]
    }


@app.get("/genes/{gene}/diplotypes")
def diplotypes(
    gene,
    q: str | None = Query(None, description="Substring that narrows the list, e.g. *4/"),
    limit: int = Query(50, ge=1, le=200)
):
    connection = state["connection"]

    known = connection.execute(
        "SELECT 1 FROM diplotype WHERE gene = ? LIMIT 1", (gene.strip().upper(),)
    ).fetchone()

    if not known:
        raise HTTPException(404, f"Unknown gene {gene!r}.")

    return search_diplotypes(connection, gene, q, limit)


@app.get("/drugs")
def drugs(
    include_all: bool = Query(
        False,
        alias="all",
        description="Include drugs whose genes this build did not load"
    )
):
    return {"drugs": list_drugs(state["connection"], answerable_only=not include_all)}


@app.post("/report")
def report(request: ReportRequest):
    if not request.diplotypes:
        raise HTTPException(400, "At least one diplotype is required.")
    return generate_report(
        state["connection"],
        request.diplotypes,
        request.drugs,
        request.specific_only
    )
