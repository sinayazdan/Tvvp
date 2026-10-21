package com.sinayazdan.tvvpn

import org.json.JSONArray
import org.json.JSONObject

/** Builds a full sing-box configuration (tun inbound + dns + routing) around one outbound. */
object SingboxConfig {

    fun build(profile: Profile): String {
        val parsed = LinkParser.parse(profile.raw)
        val outbound = parsed.outbound

        val config = JSONObject()

        config.put("log", JSONObject().apply {
            put("level", "warn")
            put("timestamp", true)
        })

        config.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "udp")
                    put("tag", "remote")
                    put("server", "8.8.8.8")
                    put("server_port", 53)
                    put("detour", "proxy")
                })
                put(JSONObject().apply {
                    put("type", "udp")
                    put("tag", "direct")
                    put("server", "1.1.1.1")
                    put("server_port", 53)
                })
            })
            put("final", "remote")
            put("strategy", "ipv4_only")
            put("independent_cache", true)
        })

        config.put("inbounds", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("address", JSONArray().apply { put("172.19.0.1/30") })
                put("mtu", 9000)
                put("auto_route", true)
                put("strict_route", true)
                put("stack", "mixed")
            })
        })

        config.put("outbounds", JSONArray().apply {
            put(outbound)
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })
        })

        config.put("route", JSONObject().apply {
            put("auto_detect_interface", true)
            put("final", "proxy")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
            })
        })

        return config.toString()
    }
}
