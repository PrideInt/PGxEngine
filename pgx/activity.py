from decimal import Decimal, InvalidOperation

POOR = "Poor Metabolizer"
INTERMEDIATE = "Intermediate Metabolizer"
NORMAL = "Normal Metabolizer"
ULTRARAPID = "Ultrarapid Metabolizer"
INDETERMINATE = "Indeterminate"

AT_LEAST = "≥"

# CPIC standardized CYP2D6 banding (Caudle et al. 2020). The 2019 revision
# moved an activity score of 1 from normal to intermediate, so the normal band
# opens at 1.25 rather than at 1.
NORMAL_FLOOR = Decimal("1.25")
NORMAL_CEILING = Decimal("2.25")


# Read a CPIC activity score, which may be exact ("1.25"), bucketed (">=3.0")
# or absent ("n/a"). Returns the value and whether it is a lower bound.
def parse_activity_score(text):
    if text is None:
        return None, False

    value = str(text).strip()

    if not value or value.lower() in ("n/a", "na", "no result", "none"):
        return None, False

    at_least = value.startswith(AT_LEAST)

    if at_least:
        value = value[len(AT_LEAST):].strip()

    try:
        return Decimal(value), at_least
    except InvalidOperation:
        return None, False


def band_activity_score(score, at_least=False):
    if score is None:
        return INDETERMINATE

    if score > NORMAL_CEILING:
        return ULTRARAPID

    # A lower bound below the ultrarapid threshold spans more than one band,
    # so the phenotype cannot be decided from it.
    if at_least:
        return INDETERMINATE

    if score == 0:
        return POOR

    if score < NORMAL_FLOOR:
        return INTERMEDIATE

    return NORMAL


# Sum the two per-allele activity values CPIC publishes for a diplotype
def total_activity_score(first, second):
    values = []

    for value in (first, second):
        if value is None or str(value).strip() == "":
            return None

        try:
            values.append(Decimal(str(value).strip()))
        except InvalidOperation:
            return None

    return sum(values)


def phenotype_from_values(first, second):
    return band_activity_score(total_activity_score(first, second))
