"""Builds app assets from public-domain sources (OurAirports, Natural Earth).
Usage: python3 tools/prepare_data.py <download_dir> <assets_dir>"""
import csv, json, sys, os

src, out = sys.argv[1], sys.argv[2]

# --- Airports: scheduled-service large/medium airports with IATA codes ---
rows = []
with open(os.path.join(src, "airports.csv"), newline="", encoding="utf-8") as f:
    for r in csv.DictReader(f):
        if r["type"] not in ("large_airport", "medium_airport"): continue
        if r["scheduled_service"] != "yes": continue
        iata = (r["iata_code"] or "").strip()
        icao = (r.get("icao_code") or r.get("gps_code") or r["ident"] or "").strip()
        if len(iata) != 3: continue
        name = r["name"].replace("|", " ").strip()
        city = (r["municipality"] or "").replace("|", " ").strip()
        try:
            lat, lon = float(r["latitude_deg"]), float(r["longitude_deg"])
            elev = int(float(r["elevation_ft"] or 0))
        except ValueError:
            continue
        rows.append((iata, icao, name, city, r["iso_country"], f"{lat:.5f}", f"{lon:.5f}", str(elev),
                     "L" if r["type"] == "large_airport" else "M"))
rows.sort()
with open(os.path.join(out, "airports.csv"), "w", encoding="utf-8") as f:
    f.write("# Source: OurAirports (public domain). iata|icao|name|city|country|lat|lon|elev_ft|size\n")
    for r in rows: f.write("|".join(r) + "\n")
print("airports:", len(rows))

# --- Natural Earth: strip properties, round coordinates ---
def rnd(c):
    if not c: return c
    if isinstance(c[0], (int, float)): return [round(c[0], 3), round(c[1], 3)]
    return [rnd(x) for x in c]

def simplify(name, outname, keep=()):
    with open(os.path.join(src, name + ".geojson"), encoding="utf-8") as f:
        gj = json.load(f)
    feats = []
    for ft in gj["features"]:
        props = {k: ft["properties"].get(k) for k in keep if k in ft["properties"]}
        g = ft["geometry"]
        if g is None: continue
        feats.append({"type": "Feature", "properties": props,
                      "geometry": {"type": g["type"], "coordinates": rnd(g["coordinates"])}})
    with open(os.path.join(out, outname), "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "features": feats}, f, separators=(",", ":"))
    print(outname, os.path.getsize(os.path.join(out, outname)))

simplify("ne_50m_land", "ne_land.geojson")
simplify("ne_50m_admin_0_boundary_lines_land", "ne_borders.geojson")
simplify("ne_50m_lakes", "ne_lakes.geojson", ("scalerank",))
simplify("ne_50m_rivers_lake_centerlines", "ne_rivers.geojson", ("scalerank",))
