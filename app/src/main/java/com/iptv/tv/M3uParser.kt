package com.iptv.tv

import java.io.BufferedReader

object M3uParser {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    /** Satir satir okur; buyuk listelerde de bellek sisirmez. */
    fun parse(reader: BufferedReader): Library {
        val live = ArrayList<Item>()
        val movies = ArrayList<Item>()
        val series = ArrayList<Item>()

        var name = ""
        var logo = ""
        var group = ""

        reader.useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    val attrs = HashMap<String, String>()
                    attrRegex.findAll(line).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
                    val lastQuote = line.lastIndexOf('"')
                    val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    if (name.isEmpty()) name = attrs["tvg-name"] ?: "Kanal"
                    logo = attrs["tvg-logo"] ?: ""
                    group = attrs["group-title"] ?: ""
                } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                    if (group.isEmpty()) group = line.substringAfter(':').trim()
                } else if (line.startsWith("#")) {
                    continue
                } else if (name.isNotEmpty() || line.startsWith("http", true)) {
                    val g = group.ifEmpty { "Diğer" }
                    val lower = line.lowercase()
                    val kind = when {
                        "/movie/" in lower -> "movie"
                        "/series/" in lower -> "series"
                        else -> "live"
                    }
                    val item = Item(line, name.ifEmpty { "Kanal" }, logo, g, line, kind)
                    when (kind) {
                        "movie" -> movies.add(item)
                        "series" -> series.add(item)
                        else -> live.add(item)
                    }
                    name = ""; logo = ""; group = ""
                }
            }
        }
        return Library(live, movies, series, System.currentTimeMillis())
    }
}
