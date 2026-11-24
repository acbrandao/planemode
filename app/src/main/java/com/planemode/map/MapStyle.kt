package com.planemode.map

import com.planemode.model.FlightSettings
import com.planemode.model.TileProvider
import kotlinx.serialization.json.*

data class TileSourceConfig(val tileJsonUrl: String?, val glyphsUrl: String?, val offlineStyleUrl: String?) {
    val online get() = !tileJsonUrl.isNullOrBlank()
}

/**
 * Builds the planeMode aviation map style. Vector-only:
 *  - Embedded Natural Earth vectors (public domain) always available offline as a base layer.
 *  - A configurable OpenMapTiles-schema vector tile source for detail when online/cached.
 * Raster tiles are never used.
 */
object MapStyle {
    const val SRC_ROUTE = "pm-route"
    const val SRC_TRACK = "pm-track"
    const val SRC_AIRCRAFT = "pm-aircraft"
    const val SRC_UNCERTAINTY = "pm-uncertainty"
    const val SRC_AIRPORTS = "pm-airports"
    const val AIRCRAFT_ICON = "pm-aircraft-icon"
    const val FONT = "Noto Sans Regular"
    const val FONT_BOLD = "Noto Sans Bold"

    fun tileSource(s: FlightSettings) = when (s.tileProvider) {
        TileProvider.OPENFREEMAP -> TileSourceConfig(
            "https://tiles.openfreemap.org/planet",
            "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
            "https://tiles.openfreemap.org/styles/positron",
        )
        TileProvider.CUSTOM -> TileSourceConfig(s.customTileJsonUrl.ifBlank { null }, s.customGlyphsUrl.ifBlank { null }, s.customOfflineStyleUrl.ifBlank { null })
        TileProvider.NONE -> TileSourceConfig(null, null, null)
    }

    private data class Palette(
        val background: String, val land: String, val water: String, val waterLine: String, val border: String, val stateBorder: String,
        val metro: String, val park: String, val ice: String, val label: String, val labelHalo: String, val waterLabel: String,
        val country: String, val airport: String, val coast: String,
    )

    // Land and water are deliberately far apart in both hue and lightness so geography reads at a glance.
    private val light = Palette(
        background = "#7FB0DA", land = "#F1EDE1", water = "#7FB0DA", waterLine = "#5E98CC", border = "#8A7F95", stateBorder = "#B8AFC0",
        metro = "#E2DCCB", park = "#CFE3C4", ice = "#FFFFFF", label = "#3A4250", labelHalo = "#F8F6F0", waterLabel = "#2F5F8F",
        country = "#2E3440", airport = "#7E5AA6", coast = "#5E8FBF",
    )
    private val dark = Palette(
        background = "#0A2747", land = "#2E3846", water = "#0A2747", waterLine = "#1E5A8C", border = "#8796AB", stateBorder = "#4E5B6E",
        metro = "#3B4658", park = "#2C4A3A", ice = "#56677C", label = "#C9D4E2", labelHalo = "#121A26", waterLabel = "#6FA3D6",
        country = "#E2E8F0", airport = "#C3A6EC", coast = "#3F78B0",
    )

    fun build(s: FlightSettings, dark: Boolean): String {
        val p = if (dark) this.dark else light
        val src = tileSource(s)
        return buildJsonObject {
            put("version", 8)
            put("name", "planeMode aviation")
            src.glyphsUrl?.let { put("glyphs", it) }
            putJsonObject("sources") {
                geo("ne-land", "asset://ne_land.geojson")
                geo("ne-lakes", "asset://ne_lakes.geojson")
                geo("ne-rivers", "asset://ne_rivers.geojson")
                geo("ne-borders", "asset://ne_borders.geojson")
                if (src.online) putJsonObject("omt") { put("type", "vector"); put("url", src.tileJsonUrl) }
                listOf(SRC_ROUTE, SRC_TRACK, SRC_AIRCRAFT, SRC_UNCERTAINTY, SRC_AIRPORTS).forEach { id ->
                    putJsonObject(id) { put("type", "geojson"); putJsonObject("data") { put("type", "FeatureCollection"); putJsonArray("features") {} } }
                }
            }
            putJsonArray("layers") {
                layer("background", "background") { paint { put("background-color", p.background) } }
                layer("ne-land", "fill", "ne-land") { paint { put("fill-color", p.land); put("fill-antialias", true) } }
                layer("ne-lakes", "fill", "ne-lakes") { paint { put("fill-color", p.water) } }
                layer("ne-coast", "line", "ne-land") {
                    put("maxzoom", 7)
                    paint { put("line-color", p.coast); put("line-width", zoomInterp(1 to 0.6, 6 to 1.2)); put("line-opacity", 0.9) }
                }
                layer("ne-rivers", "line", "ne-rivers") {
                    put("minzoom", 3)
                    paint { put("line-color", p.waterLine); put("line-width", zoomInterp(3 to 0.4, 8 to 1.4)) }
                }
                if (src.online) {
                    layer("water", "fill", "omt", "water") { paint { put("fill-color", p.water); put("fill-outline-color", p.coast) } }
                    layer("water-coast", "line", "omt", "water") {
                        put("minzoom", 6)
                        paint { put("line-color", p.coast); put("line-width", zoomInterp(6 to 0.6, 12 to 1.4)) }
                    }
                    layer("landcover-ice", "fill", "omt", "landcover") {
                        put("filter", eq("class", "ice")); paint { put("fill-color", p.ice); put("fill-opacity", 0.7) }
                    }
                    layer("metro", "fill", "omt", "landuse") {
                        put("minzoom", 5); put("filter", inList("class", "residential", "suburb", "commercial", "industrial"))
                        paint { put("fill-color", p.metro); put("fill-opacity", zoomInterp(5 to 0.25, 9 to 0.8)) }
                    }
                    layer("park", "fill", "omt", "park") {
                        put("minzoom", 6); paint { put("fill-color", p.park); put("fill-opacity", 0.6) }
                    }
                    layer("aeroway-area", "fill", "omt", "aeroway") {
                        put("minzoom", 10); put("filter", JsonArray(listOf(JsonPrimitive("=="), JsonArray(listOf(JsonPrimitive("geometry-type"))), JsonPrimitive("Polygon"))))
                        paint { put("fill-color", if (dark) "#2A3850" else "#D3D9E2"); put("fill-opacity", 0.8) }
                    }
                    layer("aeroway-taxiway", "line", "omt", "aeroway") {
                        put("minzoom", 11); put("filter", eq("class", "taxiway"))
                        layout { put("line-cap", "round"); put("line-join", "round") }
                        paint { put("line-color", if (dark) "#5D6E88" else "#A9B4C3"); put("line-width", zoomInterp(11 to 0.6, 15 to 6.0)) }
                    }
                    layer("aeroway-runway", "line", "omt", "aeroway") {
                        put("minzoom", 9); put("filter", eq("class", "runway"))
                        layout { put("line-cap", "butt") }
                        paint { put("line-color", if (dark) "#9AA9BF" else "#6E7C8F"); put("line-width", zoomInterp(9 to 1.0, 15 to 26.0)) }
                    }
                    layer("waterway", "line", "omt", "waterway") {
                        put("minzoom", 5); put("filter", eq("class", "river"))
                        paint { put("line-color", p.waterLine); put("line-width", zoomInterp(5 to 0.6, 10 to 2.0)) }
                    }
                    layer("boundary-state", "line", "omt", "boundary") {
                        put("minzoom", 4)
                        put("filter", JsonArray(listOf(JsonPrimitive("all"), eq("admin_level", 4), eq("maritime", 0))))
                        paint { put("line-color", p.stateBorder); put("line-width", 0.6); putJsonArray("line-dasharray") { add(3); add(2) } }
                    }
                    layer("boundary-country", "line", "omt", "boundary") {
                        put("filter", JsonArray(listOf(JsonPrimitive("all"), eq("admin_level", 2), eq("maritime", 0))))
                        paint { put("line-color", p.border); put("line-width", zoomInterp(2 to 0.6, 8 to 1.6)) }
                    }
                } else {
                    layer("ne-borders", "line", "ne-borders") { paint { put("line-color", p.border); put("line-width", 0.8) } }
                }
                // ---- flight overlays ----
                layer("pm-route-line", "line", SRC_ROUTE) {
                    layout { put("line-cap", "round") }
                    paint { put("line-color", "#22D3EE"); put("line-width", 2.0); put("line-opacity", 0.75); putJsonArray("line-dasharray") { add(2); add(2) } }
                }
                layer("pm-track-casing", "line", SRC_TRACK) {
                    layout { put("line-cap", "round"); put("line-join", "round") }
                    paint { put("line-color", if (dark) "#05080F" else "#FFFFFF"); put("line-width", 7.0); put("line-opacity", 0.55) }
                }
                layer("pm-track-measured", "line", SRC_TRACK) {
                    put("filter", eq("estimated", false))
                    layout { put("line-cap", "round"); put("line-join", "round") }
                    paint { put("line-color", buildJsonArray { add("get"); add("color") }); put("line-width", 4.0) }
                }
                layer("pm-track-estimated", "line", SRC_TRACK) {
                    put("filter", eq("estimated", true))
                    layout { put("line-join", "round") }
                    paint { put("line-color", "#3C4250"); put("line-width", 4.0); putJsonArray("line-dasharray") { add(1.2); add(1.0) } }
                }
                layer("pm-uncertainty", "fill", SRC_UNCERTAINTY) {
                    paint { put("fill-color", "#F5B942"); put("fill-opacity", 0.15); put("fill-outline-color", "#F5B942") }
                }
                layer("pm-airports", "circle", SRC_AIRPORTS) {
                    paint {
                        put("circle-radius", 6.0); put("circle-color", if (dark) "#0B1426" else "#FFFFFF")
                        put("circle-stroke-width", 3.0); put("circle-stroke-color", buildJsonArray { add("get"); add("color") })
                    }
                }
                if (src.glyphsUrl != null) {
                    if (src.online) {
                        layer("water-name", "symbol", "omt", "water_name") {
                            put("filter", inList("class", "ocean", "sea", "lake"))
                            layout { put("text-field", nameExpr()); textFont(FONT); put("text-size", 12); put("text-letter-spacing", 0.15); put("text-max-width", 8) }
                            paint { put("text-color", p.waterLabel) }
                        }
                        layer("place-city", "symbol", "omt", "place") {
                            put("minzoom", 3)
                            put("filter", JsonArray(listOf(JsonPrimitive("all"), eq("class", "city"),
                                JsonArray(listOf(JsonPrimitive("<="), JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("rank"))), zoomStep())))))
                            layout { put("text-field", nameExpr()); textFont(FONT); put("text-size", zoomInterp(3 to 10.0, 8 to 14.0)); put("text-max-width", 8) }
                            paint { put("text-color", p.label); put("text-halo-color", p.labelHalo); put("text-halo-width", 1.2) }
                        }
                        layer("place-town", "symbol", "omt", "place") {
                            put("minzoom", 8); put("filter", eq("class", "town"))
                            layout { put("text-field", nameExpr()); textFont(FONT); put("text-size", 11) }
                            paint { put("text-color", p.label); put("text-halo-color", p.labelHalo); put("text-halo-width", 1.0); put("text-opacity", 0.8) }
                        }
                        layer("place-country", "symbol", "omt", "place") {
                            put("maxzoom", 7); put("filter", eq("class", "country"))
                            layout { put("text-field", nameExpr()); textFont(FONT_BOLD); put("text-size", zoomInterp(1 to 10.0, 6 to 15.0)); put("text-transform", "uppercase"); put("text-letter-spacing", 0.1); put("text-max-width", 6) }
                            paint { put("text-color", p.country); put("text-halo-color", p.labelHalo); put("text-halo-width", 1.4) }
                        }
                        layer("aerodrome", "symbol", "omt", "aerodrome_label") {
                            put("minzoom", 7); put("filter", JsonArray(listOf(JsonPrimitive("has"), JsonPrimitive("iata"))))
                            layout { put("text-field", buildJsonArray { add("get"); add("iata") }); textFont(FONT_BOLD); put("text-size", 11) }
                            paint { put("text-color", p.airport); put("text-halo-color", p.labelHalo); put("text-halo-width", 1.2) }
                        }
                    }
                    layer("pm-airport-labels", "symbol", SRC_AIRPORTS) {
                        layout { put("text-field", buildJsonArray { add("get"); add("code") }); textFont(FONT_BOLD); put("text-size", 13); put("text-offset", buildJsonArray { add(0); add(1.3) }); put("text-allow-overlap", true) }
                        paint { put("text-color", "#22D3EE"); put("text-halo-color", p.labelHalo); put("text-halo-width", 1.5) }
                    }
                }
                layer("pm-aircraft", "symbol", SRC_AIRCRAFT) {
                    layout {
                        put("icon-image", AIRCRAFT_ICON); put("icon-size", 1.0)
                        put("icon-rotate", buildJsonArray { add("get"); add("course") })
                        put("icon-rotation-alignment", "map"); put("icon-allow-overlap", true); put("icon-ignore-placement", true)
                    }
                    paint { put("icon-opacity", buildJsonArray { add("case"); add(buildJsonArray { add("get"); add("stale") }); add(0.55); add(1.0) }) }
                }
            }
        }.toString()
    }

    // ---- JSON helpers ----
    private fun JsonObjectBuilder.geo(id: String, url: String) = putJsonObject(id) { put("type", "geojson"); put("data", url); put("tolerance", 0.6) }

    private fun JsonArrayBuilder.layer(id: String, type: String, source: String? = null, sourceLayer: String? = null, block: JsonObjectBuilder.() -> Unit) {
        add(buildJsonObject {
            put("id", id); put("type", type)
            source?.let { put("source", it) }; sourceLayer?.let { put("source-layer", it) }
            block()
        })
    }

    private fun JsonObjectBuilder.paint(block: JsonObjectBuilder.() -> Unit) = putJsonObject("paint", block)
    private fun JsonObjectBuilder.layout(block: JsonObjectBuilder.() -> Unit) = putJsonObject("layout", block)
    private fun JsonObjectBuilder.textFont(f: String) = putJsonArray("text-font") { add(f) }

    private fun eq(key: String, v: Any): JsonArray = JsonArray(listOf(JsonPrimitive("=="), JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive(key))), prim(v)))
    private fun inList(key: String, vararg values: String): JsonArray = JsonArray(listOf(JsonPrimitive("match"), JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive(key))),
        JsonArray(values.map { JsonPrimitive(it) }), JsonPrimitive(true), JsonPrimitive(false)))
    private fun prim(v: Any) = when (v) { is Number -> JsonPrimitive(v); is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
    private fun nameExpr() = JsonArray(listOf(JsonPrimitive("coalesce"), JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("name:latin"))),
        JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("name_en"))), JsonArray(listOf(JsonPrimitive("get"), JsonPrimitive("name")))))
    private fun zoomInterp(vararg stops: Pair<Int, Double>) = buildJsonArray {
        add("interpolate"); add(buildJsonArray { add("linear") }); add(buildJsonArray { add("zoom") })
        stops.forEach { add(it.first); add(it.second) }
    }
    /** Allow progressively lower-ranked (smaller) cities as zoom increases. */
    private fun zoomStep() = buildJsonArray { add("step"); add(buildJsonArray { add("zoom") }); add(2); add(4); add(4); add(6); add(7); add(8); add(10) }
}
