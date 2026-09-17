package com.luis.dealstracker.sources

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Percent-encodes a query-string value, using `%20` for a space rather than `+`.
 *
 * `URLEncoder` implements *form* encoding, where a space becomes `+`. That is not universally
 * accepted in a query string, and the difference is not theoretical:
 *
 *     olimpica.com  ft=smart+tv   -> HTTP 400
 *     olimpica.com  ft=smart%20tv -> HTTP 206
 *     exito.com     either        -> HTTP 206
 *
 * Watch terms are free text and multi-word terms are entirely normal ("smart tv", "nevera no
 * frost"), so every adapter that turns a term into a query uses this. Lives here rather than in
 * one adapter's file because more than one needs it.
 */
internal fun encodeQueryValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
