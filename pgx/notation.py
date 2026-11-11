import re

NOT_A_DIPLOTYPE = "not_a_diplotype"
PLAIN = "plain"
ALTERNATE_DIPLOTYPE = "alternate_diplotype"
SUBALLELE_ANNOTATION = "suballele_annotation"
UNCERTAIN_ALLELE = "uncertain_allele"
AMBIGUOUS_CALL = "ambiguous_call"
UNKNOWN_COPY_NUMBER = "unknown_copy_number"

PLACEHOLDERS = {
    "no consensus",
    "no result",
    "duplication",
    "not tested",
    "nd",
    "na"
}

# "*1/*1 (*1/*17)" -- the parenthetical is a whole alternate diplotype
ALTERNATE = re.compile(r"^(?P<primary>[^()]+?)\s*\((?P<alternate>[^()]*/[^()]*)\)$")

# "*2(*35)/*4" or "*1 (*22)/*6" -- a suballele qualifying the allele before it
SUBALLELE = re.compile(r"(?<=[\w\d])\s*\(\s*\*[^()/;]*\s*\)")

# "*2/(*4)" -- parentheses wrapping a whole allele, meaning an uncertain call
WRAPPED_ALLELE = re.compile(r"(?<![\w\d])\(\s*(\*[^()/;]+?)\s*\)")

# A candidate must name exactly two alleles
WELL_FORMED = re.compile(r"^[^/]+/[^/]+$")

UNKNOWN_COPIES = re.compile(r"[Xx]N\b")
UPPERCASE_DUPLICATION = re.compile(r"X(\d+)")


# Split a value that offers two readings, e.g. "*1/*4 or *4/*17"
def split_alternatives(text):
    parts = re.split(r"\s+or\s+", text, flags=re.IGNORECASE)
    return [part.strip() for part in parts if part.strip()]


# Expand "(*2;*10)" into one candidate per listed allele
def expand_semicolons(text):
    match = re.search(r"\(\s*([^()]*;[^()]*)\s*\)", text)

    if not match:
        return [text]

    options = [option.strip() for option in match.group(1).split(";")]

    return [
        text[: match.start()] + option + text[match.end():]
        for option in options
        if option
    ]


def normalize_getrm(raw):
    """Translate one GeT-RM consensus call into CPIC diplotype candidates.

    Returns a dict with:
      candidates  diplotype strings to try against CPIC, best first
      note        why the value needed interpretation, or PLAIN
      raw         the original value
    An empty candidate list means the value is not a diplotype at all.
    """
    if raw is None:
        return {"raw": raw, "candidates": [], "note": NOT_A_DIPLOTYPE}

    text = str(raw).strip()

    if not text or text.lower() in PLACEHOLDERS:
        return {"raw": raw, "candidates": [], "note": NOT_A_DIPLOTYPE}

    notes = []

    # "(+hybrid)" and similar trailing commentary carry no allele information
    cleaned = re.sub(r"\(\s*\+[^()]*\)", "", text).strip()

    if cleaned != text:
        notes.append(SUBALLELE_ANNOTATION)

    # Either reading may be correct, so offer both
    alternatives = split_alternatives(cleaned)

    if len(alternatives) > 1:
        notes.append(AMBIGUOUS_CALL)

    candidates = []

    for alternative in alternatives:
        for expanded in expand_semicolons(alternative):
            if expanded != alternative:
                notes.append(AMBIGUOUS_CALL)

            match = ALTERNATE.match(expanded)

            if match:
                notes.append(ALTERNATE_DIPLOTYPE)
                primary = match.group("primary").strip()
                candidates.extend([primary, match.group("alternate").strip()])
                continue

            candidates.append(expanded)

    resolved = []

    for candidate in candidates:
        value = candidate

        # A suballele qualifies the allele before it, so strip it before
        # looking for parentheses that stand alone. CPIC keys on the parent.
        stripped = SUBALLELE.sub("", value).strip()

        if stripped != value:
            notes.append(SUBALLELE_ANNOTATION)
            value = stripped

        # A parenthesised allele standing alone is an uncertain call; the
        # allele itself is still the call being made.
        unwrapped = WRAPPED_ALLELE.sub(r"\1", value)

        if unwrapped != value:
            notes.append(UNCERTAIN_ALLELE)
            value = unwrapped

        value = UPPERCASE_DUPLICATION.sub(r"x\1", value)

        if UNKNOWN_COPIES.search(value):
            notes.append(UNKNOWN_COPY_NUMBER)

        value = value.replace(" ", "")

        if not WELL_FORMED.match(value):
            continue

        if value not in resolved:
            resolved.append(value)

    if not resolved:
        return {"raw": raw, "candidates": [], "note": NOT_A_DIPLOTYPE}

    note = notes[0] if notes else PLAIN

    return {"raw": raw, "candidates": resolved, "note": note}


# Unknown copy number means at least two copies, which CPIC splits between an
# exact x2 and a bucketed x>=3. Offer both rather than choosing one.
def expand_unknown_copies(diplotype):
    if not UNKNOWN_COPIES.search(diplotype):
        return [diplotype]

    return [
        UNKNOWN_COPIES.sub("x2", diplotype),
        UNKNOWN_COPIES.sub("x≥3", diplotype)
    ]
