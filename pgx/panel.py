import re

from .core.engine import normalize_diplotype

# // CONVENTIONS

# What an assay reports for a position it did not interrogate. The whole point
# of this module is that this is a default, not a finding.
REFERENCE = "*1"

# Duplications (*2x2) and hybrids (*36+*10, *13+*1). These do not behave like
# ordinary alleles: copy number and gene conversion are detected by different
# assay chemistry, and they do not fit an allele-pair frequency model. They are
# counted and reported apart rather than silently folded in.
STRUCTURAL = re.compile(r"x\d+|\+")

# // PANELS

# Illustrative tiers, not any particular vendor's assay — naming one would
# claim a fidelity this cannot verify. They stand in for the shape real panels
# take: a few high-impact alleles, then the common actionable set, then a broad
# research panel. The frequency-ranked sweep below is the principled measure;
# these are reference points a reader recognises.
NAMED_PANELS = {
    "CYP2C19": {
        "core":   ["*2", "*3"],
        "common": ["*2", "*3", "*4", "*5", "*6", "*7", "*8", "*17"],
        "broad":  ["*2", "*3", "*4", "*5", "*6", "*7", "*8", "*9", "*10",
                   "*17", "*35"]
    },
    "CYP2D6": {
        "core":   ["*3", "*4", "*5", "*6"],
        "common": ["*2", "*3", "*4", "*5", "*6", "*9", "*10", "*17", "*29", "*41"],
        "broad":  ["*2", "*3", "*4", "*5", "*6", "*7", "*8", "*9", "*10", "*11",
                   "*14", "*15", "*17", "*29", "*41", "*49", "*59"]
    },
    "CYP2B6": {
        "core":   ["*6"],
        "common": ["*6", "*18"],
        "broad":  ["*4", "*5", "*6", "*9", "*18"]
    }
}


def is_structural(allele):
    return bool(STRUCTURAL.search(allele))


def is_structural_diplotype(diplotype):
    return any(is_structural(allele) for allele in diplotype.split("/"))


def named_panel(gene, name):
    return list(NAMED_PANELS.get(gene, {}).get(name, []))


def panel_names(gene):
    return list(NAMED_PANELS.get(gene, {}))


def populations(connection, gene):
    rows = connection.execute(
        "SELECT DISTINCT population FROM allele_frequency "
        "WHERE gene = ? ORDER BY population",
        (gene,)
    )
    return [row[0] for row in rows]


def frequency_ranked_panel(connection, gene, population, size):
    """The `size` most common non-reference alleles in one population.

    *1 is excluded because it is not something a panel can choose to carry: it
    is what gets reported when nothing is found. CPIC also derives its
    frequency as the remainder rather than observing it, so ranking by it would
    be ranking by an artefact.
    """
    rows = connection.execute(
        "SELECT allele FROM allele_frequency "
        "WHERE gene = ? AND population = ? AND allele != ? AND frequency > 0 "
        "ORDER BY frequency DESC, allele ASC LIMIT ?",
        (gene, population, REFERENCE, size)
    )
    return [row[0] for row in rows]


def mask(diplotype, panel):
    """What a lab assaying only `panel` would report for this diplotype.

    Anything the panel does not interrogate comes back as *1, which is the
    convention an untested position is reported under.

    Matching is on the whole allele token, so a panel carrying *4 does not
    catch *4x2. Modelling copy-number detection properly is a larger job than
    this, and it is why structural diplotypes are excluded from the headline
    rather than passed through an approximation.
    """
    carried = set(panel)
    alleles = normalize_diplotype(diplotype).split("/")
    observed = [allele if allele in carried else REFERENCE for allele in alleles]

    return normalize_diplotype("/".join(observed))
