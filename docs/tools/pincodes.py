"""Builds db/data/india-pincodes.csv (the Leads page's districts and areas) from India Post's pincode directory.

The CSV is the only source of the india_pincodes table: R__india_places (a repeatable Java migration) loads it, and
loads it again whenever it changes. (V96 created the table with a first copy of this data; R__india_places replaces
it.)

Source: "All India Pincode Directory", Department of Posts, data.gov.in (Government Open Data Licence - India).
A GitHub copy of the same file: https://raw.githubusercontent.com/dropdevrahul/pincodes-india/main/pincode.csv

    python3 docs/tools/pincodes.py pincode.csv src/main/resources/db/data/india-pincodes.csv

One row per pincode:
  district, state   the district most of its post offices are in (a few pincodes cross a district line)
  places            its post office names, delivery offices first, without "B.O"/"S.O"/"H.O" (at most 6, 160 chars)
  latitude/longitude the middle of its offices (median), after dropping offices more than 25 km from a first middle
                    (the directory has some offices placed far off); empty when no office has a usable point, or
                    (a point far from the rest of its district is marked doubtful: see mark_doubtful_points)
  radius_m          how far its offices spread from that middle (the 80th percentile), 1500 to 6000 m: the Leads scan
                    searches this far around the middle
"""
import csv
import math
import re
import sys
from collections import Counter, defaultdict

CITIES = "src/main/resources/db/data/india-cities.csv"
SUFFIX = re.compile(r"\s*\b(B\.?O|S\.?O|H\.?O|P\.?O|GPO|MDG|NSH)\.?\s*$", re.I)


def nice(s):
    """
    Title Case, keeping initials as people write them: a short all-capital word in a name that isn't all capitals
    ("JJ Nagar Colony", "CCI Tandur") stays capitals, and letters before a dot are capitals ("A.I.E. R.C.Puram").
    """
    shouting = s.upper() == s
    out = []
    for w in re.split(r"(\s+|-|/|\()", s.strip()):
        if not w or w.isspace() or w in "-/(":
            out.append(w)
        elif w.lower() in ("and", "of"):
            out.append(w.lower())
        elif not shouting and w.isalpha() and w.isupper() and len(w) <= 3:
            out.append(w)
        elif "." in w:
            out.append(".".join(p[:1].upper() + p[1:].lower() for p in w.split(".")))
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

    rows = mark_doubtful_points(rows)
    with open(dst, "w", encoding="utf-8", newline="") as out:
        w = csv.writer(out)
        w.writerow(["pincode", "district", "state", "places", "latitude", "longitude", "radius_m", "doubtful"])
        for p, d, s, pl, la, lo, r, doubt in rows:
            w.writerow([p, d, s, pl, "" if la is None else la, "" if lo is None else lo, r, "true" if doubt else "false"])
    print(f"{len(rows)} pincodes written to {dst}")


def mark_doubtful_points(rows):
    """
    India Post's office coordinates are often far off (Yapral 500087 is placed 54 km away, Manikonda 500089 265 km).
    A wrong point sends a scan to the wrong place when Mapbox doesn't know the postcode, so it is marked doubtful
    (the 8th column): the scan then keeps only businesses that give this pincode. A real far-off village keeps its
    point, so it can still be searched. A point is doubtful when it is far from the middle of its district's other points:
    anywhere, more than 60 km and 4 times the district's median distance; in a city's districts (india-cities.csv),
    where areas are close together, more than 20 km and 2.5 times. Judged twice, the second time without the first
    pass's wrong points (they pull the middle towards themselves).
    """
    city_districts = set()
    with open(CITIES, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            for ds in filter(None, r["districts"].split(";")):
                city_districts.add(tuple(ds.split("/")))
    by = defaultdict(list)
    for row in rows:
        if row[4] is not None:
            by[(row[1], row[2])].append(row)
    wrong = set()
    for key, pins in by.items():
        floor, times = (20, 2.5) if key in city_districts else (60, 4)
        # Twice: the first pass's wrong points pull the middle and the spread; the second judges without them
        for _ in range(2):
            kept = [r for r in pins if r[0] not in wrong]
            if len(kept) < 3:
                break
            middle = (median([r[4] for r in kept]), median([r[5] for r in kept]))
            typical = median([km(middle, (r[4], r[5])) for r in kept])
            wrong.update(r[0] for r in kept if km(middle, (r[4], r[5])) > max(floor, times * typical))
    print(f"{len(wrong)} doubtful locations marked")
    return [(p, d, s, pl, la, lo, r, p in wrong) for p, d, s, pl, la, lo, r in rows]


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
