"""Builds V96__india_pincodes.sql (the Leads page's districts and areas) from India Post's pincode directory.

Source: "All India Pincode Directory", Department of Posts, data.gov.in (Government Open Data Licence - India).
A GitHub copy of the same file: https://raw.githubusercontent.com/dropdevrahul/pincodes-india/main/pincode.csv

    python3 docs/tools/pincodes.py pincode.csv src/main/resources/db/migration/V96__india_pincodes.sql

One row per pincode:
  district, state   the district most of its post offices are in (a few pincodes cross a district line)
  places            its post office names, delivery offices first, without "B.O"/"S.O"/"H.O" (at most 6, 160 chars)
  latitude/longitude the middle of its offices (median), after dropping offices more than 25 km from a first middle
                    (the directory has some offices placed far off); null when no office has a usable point
  radius_m          how far its offices spread from that middle (the 80th percentile), 1500 to 6000 m: the Leads scan
                    searches this far around the middle
"""
import csv
import math
import re
import sys
from collections import Counter, defaultdict

SUFFIX = re.compile(r"\s*\b(B\.?O|S\.?O|H\.?O|P\.?O|GPO|MDG|NSH)\.?\s*$", re.I)


def nice(s):
    out = []
    for w in re.split(r"(\s+|-|/|\()", s.strip()):
        if not w or w.isspace() or w in "-/(":
            out.append(w)
        elif w.lower() in ("and", "of"):
            out.append(w.lower())
        else:
            out.append(w[0].upper() + w[1:].lower())
    return re.sub(r"\s+", " ", "".join(out)).strip()


def point(r):
    try:
        la, lo = float(r["Latitude"]), float(r["Longitude"])
    except (ValueError, TypeError):
        return None
    return (la, lo) if 6 <= la <= 37.6 and 68 <= lo <= 97.5 else None


def km(a, b):
    dl = math.radians(b[0] - a[0])
    dn = math.radians(b[1] - a[1])
    h = math.sin(dl / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dn / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(h))


def median(xs):
    xs = sorted(xs)
    n = len(xs)
    return xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) / 2


def sql(s):
    return "'" + s.replace("'", "''") + "'"


def main(src, dst):
    pins = defaultdict(list)
    with open(src, encoding="utf-8", errors="replace") as f:
        for r in csv.DictReader(f):
            p = (r.get("Pincode") or "").strip()
            if re.fullmatch(r"[1-9]\d{5}", p):
                pins[p].append(r)
    rows = []
    for p in sorted(pins):
        offices = pins[p]
        district, state = Counter((o["District"].strip(), o["StateName"].strip()) for o in offices).most_common(1)[0][0]
        names = []
        for o in sorted(offices, key=lambda o: (o["Delivery"].strip() != "Delivery", o["OfficeType"] not in ("PO", "HO"))):
            n = nice(SUFFIX.sub("", o["OfficeName"]))
            if n and n not in names:
                names.append(n)
        places = ""
        for n in names[:6]:
            nxt = n if not places else places + ", " + n
            if len(nxt) > 160:
                break
            places = nxt
        pts = [pt for pt in (point(o) for o in offices) if pt]
        lat = lng = None
        radius = 1500
        if pts:
            mid = (median([a for a, _ in pts]), median([b for _, b in pts]))
            near = [pt for pt in pts if km(mid, pt) <= 25] or [mid]
            mid = (median([a for a, _ in near]), median([b for _, b in near]))
            d = sorted(km(mid, pt) for pt in near)
            spread = d[min(len(d) - 1, int(len(d) * 0.8))] * 1000
            radius = int(max(1500, min(6000, spread)))
            lat, lng = round(mid[0], 6), round(mid[1], 6)
        rows.append((p, nice(district), nice(state), places or p, lat, lng, radius))

    with open(dst, "w", encoding="utf-8") as out:
        out.write("SET LOCAL lock_timeout = '3s';\n\n")
        out.write("-- India's pincodes for the admin website's Leads page (districts and areas). Built by docs/tools/pincodes.py\n")
        out.write("-- from India Post's All India Pincode Directory (Department of Posts, data.gov.in, Government Open Data\n")
        out.write("-- Licence - India). Read-only reference data: the app never writes it.\n")
        out.write("--   places: post office names, delivery offices first; latitude/longitude: the middle of its offices (null if\n")
        out.write("--   none has a usable point: such a pincode can't be scanned); radius_m: how far the scan searches around it.\n")
        out.write("CREATE TABLE india_pincodes (\n")
        out.write("    pincode    VARCHAR(6)   PRIMARY KEY CHECK (pincode ~ '^[1-9][0-9]{5}$'),\n")
        out.write("    district   VARCHAR(80)  NOT NULL,\n")
        out.write("    state      VARCHAR(80)  NOT NULL,\n")
        out.write("    places     VARCHAR(200) NOT NULL,\n")
        out.write("    latitude   NUMERIC(9,6) CHECK (latitude BETWEEN 6 AND 37.6),\n")
        out.write("    longitude  NUMERIC(9,6) CHECK (longitude BETWEEN 68 AND 97.5),\n")
        out.write("    radius_m   INTEGER      NOT NULL CHECK (radius_m BETWEEN 500 AND 10000),\n")
        out.write("    CHECK ((latitude IS NULL) = (longitude IS NULL))\n);\n\n")
        for i in range(0, len(rows), 1000):
            out.write("INSERT INTO india_pincodes (pincode, district, state, places, latitude, longitude, radius_m) VALUES\n")
            chunk = rows[i:i + 1000]
            out.write(",\n".join("(" + ", ".join([sql(p), sql(d), sql(s), sql(pl),
                                                  "NULL" if la is None else repr(la),
                                                  "NULL" if lo is None else repr(lo), str(r)]) + ")"
                                 for p, d, s, pl, la, lo, r in chunk))
            out.write(";\n\n")
    print(f"{len(rows)} pincodes, {len({(r[1], r[2]) for r in rows})} districts -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
