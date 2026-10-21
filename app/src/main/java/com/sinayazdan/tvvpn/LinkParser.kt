package com.sinayazdan.tvvpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Parses share links (vless/vmess/trojan/ss/hysteria2/tuic) into sing-box outbounds. */
object LinkParser {

    data class Parsed(val name: String, val protocol: String, val outbound: JSONObject) {
        /** host:port for display, best effort */
        val endpoint: String
            get() = "${outbound.optString("server")}:${outbound.optInt("server_port")}"
    }

    fun parse(rawInput: String): Parsed {
        val link = rawInput.trim()
        return when {
            link.startsWith("vmess://") -> parseVmess(link)
            link.startsWith("vless://") -> parseUriLink(link, "vless")
            link.startsWith("trojan://") -> parseUriLink(link, "trojan")
            link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseUriLink(link, "hysteria2")
            link.startsWith("tuic://") -> parseUriLink(link, "tuic")
            link.startsWith("ss://") -> parseShadowsocks(link)
            else -> throw IllegalArgumentException("نوع لینک پشتیبانی نمی‌شود")
        }
    }

    fun displayName(link: String, fallback: String): String {
        val fragment = link.substringAfter("#", "")
        if (fragment.isNotBlank()) {
            return runCatching { URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment)
        }
        return fallback
    }

    // ---------------- VMess ----------------

    private fun parseVmess(link: String): Parsed {
        var body = link.removePrefix("vmess://").substringBefore("#").substringBefore("?")
        body = body.replace('-', '+').replace('_', '/')
        while (body.length % 4 != 0) body += "="
        val json = JSONObject(String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8))
        val name = json.optString("ps", "VMess")
        val outbound = JSONObject()
        outbound.put("type", "vmess")
        outbound.put("tag", "proxy")
        outbound.put("server", json.getString("add"))
        outbound.put("server_port", json.optString("port", "443").toInt())
        outbound.put("uuid", json.getString("id"))
        outbound.put("alter_id", json.optString("aid", "0").toIntOrNull() ?: 0)
        outbound.put("security", json.optString("scy", "auto").ifBlank { "auto" })

        val network = json.optString("net", "tcp")
        outbound.put("transport", transport(network, json.optString("type"), json))
        if (json.optString("tls") == "tls") {
            outbound.put("tls", tlsObject(json.optString("sni"), json.optString("alpn"), json.optString("fp"), false, null))
        }
        return Parsed(name, "VMess", outbound)
    }

    // ---------------- URI-style links ----------------

    private fun parseUriLink(link: String, scheme: String): Parsed {
        val normalized = if (scheme == "hysteria2" && link.startsWith("hy2://")) {
            link.replaceFirst("hy2://", "hysteria2://")
        } else link
        val uri = URI(normalized)
        val userInfo = uri.userInfo?.let { decodeComponent(it) } ?: ""
        val host = uri.host ?: throw IllegalArgumentException("سرور در لینک نیست")
        val port = if (uri.port > 0) uri.port else 443
        val query = parseQuery(uri.rawQuery ?: "")
        val name = displayName(normalized, scheme.uppercase())

        val outbound = JSONObject()
        outbound.put("tag", "proxy")
        outbound.put("server", host)
        outbound.put("server_port", port)

        when (scheme) {
            "vless" -> {
                outbound.put("type", "vless")
                outbound.put("uuid", userInfo)
                query["flow"]?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }
                outbound.put("packet_encoding", "xudp")
                outbound.put("transport", transport(query["type"] ?: "tcp", query["headerType"], query))
                buildTls(outbound, query)
            }
            "trojan" -> {
                outbound.put("type", "trojan")
                outbound.put("password", userInfo)
                outbound.put("transport", transport(query["type"] ?: "tcp", query["headerType"], query))
                val tlsQuery = query.toMutableMap()
                if (tlsQuery["security"] == null) tlsQuery["security"] = "tls"
                buildTls(outbound, tlsQuery)
            }
            "hysteria2" -> {
                outbound.put("type", "hysteria2")
                outbound.put("password", userInfo)
                query["obfs"]?.takeIf { it.isNotBlank() }?.let { obfs ->
                    val obfsJson = JSONObject()
                    obfsJson.put("type", obfs)
                    query["obfs-password"]?.let { obfsJson.put("password", it) }
                    outbound.put("obfs", obfsJson)
                }
                val tls = JSONObject()
                tls.put("enabled", true)
                query["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
                tls.put("insecure", query["insecure"] == "1" || query["allowInsecure"] == "1")
                query["alpn"]?.takeIf { it.isNotBlank() }?.let {
                    tls.put("alpn", JSONArray(it.split(",")))
                }
                outbound.put("tls", tls)
            }
            "tuic" -> {
                outbound.put("type", "tuic")
                val parts = userInfo.split(":", limit = 2)
                outbound.put("uuid", parts.getOrNull(0) ?: "")
                outbound.put("password", parts.getOrNull(1) ?: "")
                outbound.put("congestion_control", query["congestion_control"] ?: "bbr")
                outbound.put("udp_relay_mode", query["udp_relay_mode"] ?: "native")
                val tls = JSONObject()
                tls.put("enabled", true)
                query["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
                tls.put("insecure", query["insecure"] == "1" || query["allowInsecure"] == "1")
                query["alpn"]?.takeIf { it.isNotBlank() }?.let {
                    tls.put("alpn", JSONArray(it.split(",")))
                }
                outbound.put("tls", tls)
            }
        }
        val label = when (scheme) {
            "vless" -> "VLESS"
            "trojan" -> "Trojan"
            "hysteria2" -> "Hysteria2"
            "tuic" -> "TUIC"
            else -> scheme
        }
        return Parsed(name, label, outbound)
    }

    // ---------------- Shadowsocks ----------------

    private fun parseShadowsocks(link: String): Parsed {
        val name = displayName(link, "Shadowsocks")
        var body = link.removePrefix("ss://").substringBefore("#")
        body = body.substringBefore("?")
        val method: String
        val password: String
        val host: String
        val port: Int
        if (body.contains("@")) {
            val userPart = body.substringBeforeLast("@")
            val serverPart = body.substringAfterLast("@")
            val decoded = decodeBase64OrPlain(userPart)
            method = decoded.substringBefore(":")
            password = decoded.substringAfter(":", "")
            host = serverPart.substringBefore(":")
            port = serverPart.substringAfter(":").substringBefore("/").toInt()
        } else {
            val decoded = decodeBase64OrPlain(body)
            // method:password@host:port
            val creds = decoded.substringBeforeLast("@")
            val serverPart = decoded.substringAfterLast("@")
            method = creds.substringBefore(":")
            password = creds.substringAfter(":", "")
            host = serverPart.substringBefore(":")
            port = serverPart.substringAfter(":").toInt()
        }
        val outbound = JSONObject()
        outbound.put("type", "shadowsocks")
        outbound.put("tag", "proxy")
        outbound.put("server", host)
        outbound.put("server_port", port)
        outbound.put("method", method)
        outbound.put("password", password)
        return Parsed(name, "Shadowsocks", outbound)
    }

    // ---------------- helpers ----------------

    private fun buildTls(outbound: JSONObject, query: Map<String, String>) {
        val security = query["security"] ?: ""
        if (security == "tls" || security == "reality") {
            val tls = JSONObject()
            tls.put("enabled", true)
            query["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
            tls.put("insecure", query["insecure"] == "1" || query["allowInsecure"] == "1")
            query["alpn"]?.takeIf { it.isNotBlank() }?.let {
                tls.put("alpn", JSONArray(it.split(",")))
            }
            val fingerprint = query["fp"] ?: "chrome"
            val utls = JSONObject()
            utls.put("enabled", true)
            utls.put("fingerprint", fingerprint)
            tls.put("utls", utls)
            if (security == "reality") {
                val reality = JSONObject()
                reality.put("enabled", true)
                query["pbk"]?.let { reality.put("public_key", it) }
                query["sid"]?.let { reality.put("short_id", it) }
                tls.put("reality", reality)
            }
            outbound.put("tls", tls)
        }
    }

    private fun tlsObject(sni: String, alpn: String, fingerprint: String, insecure: Boolean, reality: JSONObject?): JSONObject {
        val tls = JSONObject()
        tls.put("enabled", true)
        if (sni.isNotBlank()) tls.put("server_name", sni)
        tls.put("insecure", insecure)
        if (alpn.isNotBlank()) tls.put("alpn", JSONArray(alpn.split(",")))
        if (fingerprint.isNotBlank()) {
            val utls = JSONObject()
            utls.put("enabled", true)
            utls.put("fingerprint", fingerprint)
            tls.put("utls", utls)
        }
        if (reality != null) tls.put("reality", reality)
        return tls
    }

    /** V2Ray transports shared by vmess/vless/trojan */
    private fun transport(network: String, headerType: String?, values: Map<String, String>): JSONObject? {
        val type = network.lowercase()
        val result = JSONObject()
        when (type) {
            "tcp" -> {
                if (headerType == "http") {
                    result.put("type", "http")
                    values["host"]?.takeIf { it.isNotBlank() }?.let {
                        result.put("host", JSONArray(it.split(",")))
                    }
                    values["path"]?.let { result.put("path", it) }
                    return result
                }
                return null
            }
            "ws" -> {
                result.put("type", "ws")
                values["path"]?.takeIf { it.isNotBlank() }?.let { result.put("path", it) }
                values["host"]?.takeIf { it.isNotBlank() }?.let { host ->
                    val headers = JSONObject()
                    headers.put("Host", host)
                    result.put("headers", headers)
                }
            }
            "grpc" -> {
                result.put("type", "grpc")
                values["serviceName"]?.takeIf { it.isNotBlank() }?.let { result.put("service_name", it) }
            }
            "http", "h2" -> {
                result.put("type", "http")
                values["host"]?.takeIf { it.isNotBlank() }?.let {
                    result.put("host", JSONArray(it.split(",")))
                }
                values["path"]?.let { result.put("path", it) }
            }
            "httpupgrade" -> {
                result.put("type", "httpupgrade")
                values["path"]?.takeIf { it.isNotBlank() }?.let { result.put("path", it) }
                values["host"]?.takeIf { it.isNotBlank() }?.let { result.put("host", it) }
            }
            "splithttp", "xhttp" -> {
                result.put("type", "splithttp")
                values["path"]?.takeIf { it.isNotBlank() }?.let { result.put("path", it) }
                values["host"]?.takeIf { it.isNotBlank() }?.let { result.put("host", it) }
            }
            "quic" -> {
                result.put("type", "quic")
            }
            else -> return null
        }
        return result
    }

    private fun transport(network: String, headerType: String?, vmessJson: JSONObject): JSONObject? {
        val values = mapOf(
            "path" to vmessJson.optString("path"),
            "host" to vmessJson.optString("host"),
            "serviceName" to vmessJson.optString("path"),
        )
        return transport(network, headerType, values)
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = decodeComponent(pair.substringBefore("="))
            val value = decodeComponent(pair.substringAfter("=", ""))
            key to value
        }.toMap()
    }

    private fun decodeComponent(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun decodeBase64OrPlain(value: String): String {
        val trimmed = value.trim()
        if (trimmed.contains(":")) return decodeComponent(trimmed)
        var body = trimmed.replace('-', '+').replace('_', '/')
        while (body.length % 4 != 0) body += "="
        return runCatching { String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8) }
            .getOrDefault(decodeComponent(trimmed))
    }
}
