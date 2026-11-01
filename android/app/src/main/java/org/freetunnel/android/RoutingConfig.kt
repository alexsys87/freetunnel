package org.freetunnel.android

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

object RoutingConfig {
    internal val platformExcludedRoutes = listOf("0.0.0.0/8", "224.0.0.0/3")
    private fun quote(text: String): String = "\"" + buildString {
        text.forEach { c -> append(when (c) {
            '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> if (c.code < 32 || c.code == 127) "\\u%04x".format(c.code) else c.toString()
        }) }
    } + "\""
    private fun render(item: Any): String = when (item) {
        is String -> quote(item)
        is TomlTable -> item.keySet().joinToString(", ", "{ ", " }") { "${quote(it)} = ${render(item.get(listOf(it))!!)}" }
        is TomlArray -> (0 until item.size()).joinToString(", ", "[", "]") { render(item.get(it)) }
        is List<*> -> item.joinToString(", ", "[", "]") { render(it!!) }
        is Double -> when { item.isNaN() -> "nan"; item == Double.POSITIVE_INFINITY -> "inf"; item == Double.NEGATIVE_INFINITY -> "-inf"; else -> item.toString() }
        is java.time.LocalTime -> item.format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME)
        is java.time.LocalDateTime -> item.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        is java.time.OffsetDateTime -> item.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        else -> item.toString()
    }
    private fun key(text: String) = if (text.matches(Regex("[A-Za-z0-9_-]+"))) text else quote(text)
    // Keys at a path in `drop` are left out, along with everything under them, and a
    // key at a path in `overrides` is written with the value given there.
    private fun document(root: Map<String, Any>, drop: Set<List<String>> = emptySet(),
                         overrides: Map<List<String>, Any> = emptyMap()): String = buildString {
        fun tableArray(value: Any): List<TomlTable>? {
            if (value !is TomlArray || value.isEmpty) return null
            val values = (0 until value.size()).map { value.get(it) }
            return if (values.all { it is TomlTable }) values.map { it as TomlTable } else null
        }
        fun writeTable(all: Map<String, Any>, path: List<String>, arrayElement: Boolean = false) {
            val values = all.filterKeys { (path + it) !in drop }.mapValues { (name, value) -> overrides[path + name] ?: value }
            if (path.isNotEmpty()) {
                append(if (arrayElement) "\n[[" else "\n[")
                append(path.joinToString(".", transform = ::key))
                append(if (arrayElement) "]]\n" else "]\n")
            }
            values.filterValues { it !is TomlTable && tableArray(it) == null }.forEach { (name, value) ->
                append(key(name)).append(" = ").append(render(value)).append('\n')
            }
            values.forEach { (name, value) ->
                if (value is TomlTable) writeTable(value.keySet().associateWith { value.get(listOf(it))!! }, path + name)
                tableArray(value)?.forEach { table ->
                    writeTable(table.keySet().associateWith { table.get(listOf(it))!! }, path + name, true)
                }
            }
        }
        writeTable(root, emptyList())
    }
    private fun parse(source: String): TomlTable {
        require(source.length <= 1_048_576) { "Конфигурация слишком большая (максимум 1 МБ)" }
        val parsed = Toml.parse(source)
        require(!parsed.hasErrors()) { "Ошибка TOML: ${parsed.errors().firstOrNull()?.position()}" }
        require(parsed.getTable("endpoint") != null) { "В конфигурации отсутствует раздел [endpoint]" }
        return parsed
    }
    @JvmStatic fun validate(source: String): TomlTable {
        val parsed = parse(source)
        val endpoint = parsed.getTable("endpoint")!!
        require(!endpoint.getString("hostname").isNullOrBlank()) { "Укажите endpoint.hostname" }
        require(endpoint.getString("username") != null && endpoint.getString("password") != null) { "Укажите имя пользователя и пароль сервера" }
        val addresses = endpoint.getArray("addresses")
        require(addresses != null && addresses.size() > 0 && (0 until addresses.size()).all {
            val address = addresses.getString(it)
            val port = address.substringAfterLast(':', "").toIntOrNull()
            port != null && port in 1..65535 && address.substringBeforeLast(':').isNotBlank()
        }) { "Укажите addresses в формате адрес:порт" }
        require(endpoint.getString("upstream_protocol")?.let { it in listOf("http2", "http3") } != false) { "Укажите upstream_protocol: http2 или http3" }
        require(parsed.getString("vpn_mode")?.let { it in listOf("general", "selective") } != false) { "Неизвестный vpn_mode" }
        val rules = parsed.getArray("exclusions")?.let { a -> (0 until a.size()).map { a.getString(it) } }.orEmpty()
        require(rules.none { SplitRules.subnet(it)?.substringAfterLast('/')?.toInt() == 0 }) { "Default routes cannot be destination rules" }
        if (parsed.getString("vpn_mode") != "selective") {
            require(!SplitRules.coversAddressFamily(rules + platformExcludedRoutes)) { "Destination exclusions cover an entire address family" }
        }
        val tun = parsed.getTable("listener.tun") ?: error("Missing listener.tun")
        val included = tun.getArray("included_routes") ?: error("Missing included_routes")
        require(included.size() > 0) { "Empty included_routes" }
        val routes = (0 until included.size()).map { included.getString(it) }
        val excluded = tun.getArray("excluded_routes") ?: error("Missing excluded_routes")
        val excludedRoutes = (0 until excluded.size()).map { excluded.getString(it) }
        val allRoutes = routes + excludedRoutes
        require(allRoutes.all { SplitRules.subnet(it) != null && '/' in it }) { "Invalid TUN route" }
        require(!SplitRules.coversAddressFamily(excludedRoutes + platformExcludedRoutes)) { "Excluded TUN routes cover an entire address family" }
        val ipv6 = (endpoint.getBoolean("has_ipv6") ?: false) && routes.any { ':' in it }
        val mtu = tun.getLong("mtu_size") ?: error("Missing mtu_size")
        require(mtu in (if (ipv6) 1280L else 576L)..65535L) { "Invalid TUN MTU" }
        return parsed
    }

    /**
     * An imported file as it will be saved, whether a subscription was dropped from
     * it, whether its certificate held more than certificates, which were kept alone,
     * and the name it gives its server, if it gives one.
     */
    data class Import @JvmOverloads constructor(val toml: String, val subscriptionNotKept: Boolean,
                                                val certificateIgnored: Boolean = false, val name: String? = null)

    /** A file refused for a reason the user can act on; the message is shown as it is. */
    class ImportRefused(@JvmField val messageResource: Int) : IllegalArgumentException("Import refused")

    // Where TrustTunnel 1.3 writes a subscription address: the client's own config
    // as an [endpoint.subscription] table, the endpoint's export as subscription_url,
    // which ends up at the top or inside [endpoint]. The address carries the VPN
    // password, and this version cannot follow a subscription, so it is not kept.
    private val subscriptionKeys = setOf(listOf("subscription_url"), listOf("endpoint", "subscription"),
        listOf("endpoint", "subscription_url"))

    private fun lookup(table: TomlTable, path: List<String>): Any? {
        var current = table
        for (name in path.dropLast(1)) current = current.get(listOf(name)) as? TomlTable ?: return null
        return current.get(listOf(path.last()))
    }

    // A server that can be connected to without its subscription: the four settings
    // a link has to carry, none of them empty, as DeepLinkImport reads them.
    private fun hasStaticServer(endpoint: TomlTable): Boolean {
        fun text(name: String) = endpoint.get(listOf(name)) as? String
        val addresses = endpoint.get(listOf("addresses")) as? TomlArray
        return !text("hostname").isNullOrBlank() && !text("username").isNullOrEmpty() &&
            !text("password").isNullOrEmpty() && addresses != null && !addresses.isEmpty
    }

    // Whether `b` holds exactly what `a` does, apart from the keys at a path in `drop`.
    // Those in `replaced` as well may be in `b` with a new value, which the caller checks.
    private fun same(a: Any?, b: Any?, path: List<String> = emptyList(), drop: Set<List<String>> = emptySet(),
                     replaced: Set<List<String>> = emptySet()): Boolean =
        when {
            a is TomlTable && b is TomlTable -> {
                val kept = a.keySet().filter { (path + it) !in drop }
                kept.toSet() == b.keySet().filter { (path + it) !in replaced }.toSet() &&
                    kept.all { same(a.get(listOf(it)), b.get(listOf(it)), path + it, drop, replaced) }
            }
            a is TomlArray && b is TomlArray -> a.size() == b.size() && (0 until a.size()).all { same(a.get(it), b.get(it)) }
            else -> a == b
        }

    private val certificatePath = listOf("endpoint", "certificate")

    // [endpoint].certificate with only its certificates kept, when it holds more than
    // those, such as the private key a server can keep in the same file; null when
    // there is nothing to take out. One with no certificate in it at all is refused:
    // the file meant to pin one, and the connection would only fail later.
    private fun certificates(endpoint: TomlTable): Certificates.Kept? {
        val text = endpoint.get(listOf("certificate")) as? String ?: return null
        val kept = Certificates.fromPem(text)
        if (kept.dropped && kept.der.isEmpty())
            throw ImportRefused(R.string.text_the_configuration_contains_an_invalid_certificate)
        return kept.takeIf { it.dropped }
    }

    // The TOML file a TrustTunnel endpoint exports: the keys of [endpoint] at the top
    // with no table at all, which upstream means to be pasted under an [endpoint]
    // header. Taken strictly, so that no client config is read as one: the core
    // reads its server from [endpoint] alone, and every spelling of that, a header,
    // a dotted key or an inline table, puts a table at the top. Null for any other
    // file, which is left to validate().
    private fun endpointExport(root: TomlTable): Import? {
        fun table(value: Any?) =
            value is TomlTable || (value is TomlArray && (0 until value.size()).any { value.get(it) is TomlTable })
        if (root.get(listOf("endpoint")) != null || root.keySet().any { table(root.get(listOf(it))) }) return null
        if (listOf("hostname", "username", "password").any { root.get(listOf(it)) !is String } ||
            root.get(listOf("addresses")) !is TomlArray) return null
        val imported = try {
            DeepLinkImport.fromEndpointExport(root)
        } catch (refused: DeepLinkImport.ParseException) {
            throw ImportRefused(refused.messageResource)
        }
        return Import(imported.toml, imported.subscriptionNotKept, imported.certificateIgnored, imported.name.ifEmpty { null })
    }

    /**
     * Checks an imported TOML file before it is confirmed. A file is kept as it was
     * written unless it names a subscription or its certificate holds more than
     * certificates; then only the subscription and whatever is not a certificate
     * are taken out of it, and everything else stays as written. An endpoint's
     * export is turned into the configuration a link with its settings gives.
     */
    @JvmStatic fun cleanImport(source: String): Import {
        // Anything unreadable is left to validate(), which says what is wrong.
        if (source.length > 1_048_576) return Import(source, false)
        val parsed = Toml.parse(source)
        if (parsed.hasErrors()) return Import(source, false)
        val endpoint = parsed.get(listOf("endpoint")) as? TomlTable ?: return endpointExport(parsed) ?: Import(source, false)
        // A real auth key needs TrustTunnel 1.3: core 1.1.7 ignores it, and the server
        // then refuses every connection without saying why. The endpoint writes an
        // empty one into every export, so only a key with something in it is refused.
        // Anything else that is there, blank or not a string, is refused too: no core
        // would read it as no key.
        val authKey = endpoint.get(listOf("client_random_auth_key"))
        if (authKey != null && authKey != "") throw ImportRefused(R.string.text_this_server_needs_a_newer_trusttunnel_core)
        val subscription = subscriptionKeys.any { lookup(parsed, it) != null }
        // With a subscription the server's own settings are optional, as in a link,
        // and a file without them could only work once the subscription is fetched.
        if (subscription && !hasStaticServer(endpoint)) throw ImportRefused(R.string.text_subscription_links_are_not_supported_yet)
        val certificate = certificates(endpoint)
        if (!subscription && certificate == null) return Import(source, false)
        val drop = if (certificate == null) subscriptionKeys else subscriptionKeys + setOf(certificatePath)
        val replace = certificate?.let { mapOf(certificatePath to "\"\"\"\n${it.pem}\"\"\"") }.orEmpty()
        // Line by line first, which keeps comments, layout and the other keys as they
        // were. A spelling that misses, such as an inline [endpoint] table, shows up
        // when the result is compared with the file as parsed. Such a file is written
        // out again without what was taken out: every other value stays, its layout
        // does not.
        val lines: String? = TomlLines.edit(source, drop, replace)
        val kept = lines?.let { Toml.parse(it) }
        if (lines != null && kept != null && !kept.hasErrors() && same(parsed, kept, emptyList(), drop, replace.keys) &&
            (certificate == null || lookup(kept, certificatePath) == certificate.pem))
            return Import(lines, subscription, certificate != null)
        val root = linkedMapOf<String, Any>()
        parsed.keySet().forEach { root[it] = parsed.get(listOf(it))!! }
        val overrides = certificate?.let { mapOf(certificatePath to it.pem) }.orEmpty()
        return Import(document(root, subscriptionKeys, overrides), subscription, certificate != null)
    }

    /**
     * A session's plan. [toml] is the config as checked (validate, and the service's own checks of it); [coreToml] is what
     * the core is given, the same with the geo lists' entries added to its exclusions. Those can run to hundreds of
     * thousands, far past what the checks take (a 1 MB config), and are GeoLists' own, already checked as they were read.
     */
    data class Plan @JvmOverloads constructor(val toml: String, val apps: List<String>, val allowApps: Boolean, val warning: Int?,
                                              val coreToml: String = toml, val geoVersion: String = "")

    /** Geo routing for a session: the mode, and its entries (GeoStore.entries), null while there are none yet. */
    data class Geo(val mode: GeoRouting.Mode, val entries: String?)

    /**
     * The plan for a session on this config with the rules of [profile], the split profile it uses: the
     * domains and applications that profile lists (SplitStore.rules), with the on and off, the mode and the kill switch,
     * which are the app's and not a profile's. The app, the tile and Always-on all come here with the profile of the
     * config they run, so that they route alike. An application that is no longer installed is left out of the
     * plan and stays in the profile, where it can be removed.
     */
    @JvmStatic fun prepare(context: android.content.Context, source: String, profile: String): Plan {
        val prefs = context.getSharedPreferences("freetunnel", android.content.Context.MODE_PRIVATE)
        val rules = SplitStore.rules(SplitPrefs(prefs), null, profile)
        val apps = rules.apps.filter { it != context.packageName }.filter {
            try { context.packageManager.getApplicationInfo(it, 0); true }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        }
        // Geo routing's entries, and their fingerprint, by which GeoUpdater tells whether the session has to take in new ones.
        val geoMode = GeoUpdater.mode(prefs)
        val store = GeoUpdater.store(context)
        val sources = GeoUpdater.sources(prefs)
        val geo = if (geoMode == GeoRouting.Mode.OFF) null else Geo(geoMode, store.entries(geoMode, sources))
        val plan = prepare(source, prefs.getBoolean("split_enabled", false), prefs.getBoolean("through", false),
            rules.domains, apps, prefs.getBoolean("killswitch", true),
            ExcludedRoutes.load(prefs.getString(ExcludedRoutes.KEY, null), ExcludedRoutes.defaults(context)), geo)
        return if (geo?.entries == null) plan else plan.copy(geoVersion = store.version(geoMode, sources))
    }

    /**
     * The plan for these settings. [excludedRoutes] are Settings' Excluded routes (ExcludedRoutes), which are added to the
     * config's own listener.tun.excluded_routes: those of them that are subnets and not a /0, once each, and the IPv6 ones only
     * where the session has IPv6. The result is checked as the config itself is, so that routes which together cover every
     * address of a family are refused, as they would be in the config.
     */
    @JvmStatic @JvmOverloads fun prepare(source: String, enabled: Boolean, through: Boolean,
                           domains: List<String>, apps: List<String>, killSwitch: Boolean,
                           excludedRoutes: List<String> = emptyList(), geo: Geo? = null): Plan {
        val parsed = validate(source)
        if (geo != null && geo.mode != GeoRouting.Mode.OFF)
            return prepareGeo(parsed, geo, enabled, through, domains, apps, killSwitch, excludedRoutes)
        val originalMode = parsed.getString("vpn_mode") ?: "general"
        val original = parsed.getArray("exclusions")?.let { a -> (0 until a.size()).map { a.getString(it) } }.orEmpty()
        val requestedMode = if (enabled) { if (through) "selective" else "general" } else originalMode
        var rules = ((if (!enabled || originalMode == requestedMode) original else emptyList()) +
            if (enabled) SplitRules.normalize(domains.joinToString("\n")).map { SplitRules.core(it)!! } else emptyList()).distinct()
        var selectedApps = if (enabled) apps.distinct().sorted() else emptyList()
        var allowApps = enabled && through && selectedApps.isNotEmpty()
        var mode = requestedMode
        var warning: Int? = null
        if (mode == "selective") {
            when {
                selectedApps.isNotEmpty() && rules.isNotEmpty() -> {
                    // Android's per-app allowlist intersects with destination routing.
                    // The adapter cannot express a union. Protect all traffic and tell
                    // the user explicitly instead of silently losing either ruleset.
                    mode = "general"; rules = emptyList(); selectedApps = emptyList(); allowApps = false
                    warning = R.string.text_applications_and_addresses_are_selected_full_vpn_pro
                }
                selectedApps.isNotEmpty() -> mode = "general"
                rules.isEmpty() -> {
                    mode = "general"
                    warning = R.string.text_no_through_vpn_rules_full_vpn_is_enabled
                }
            }
        }
        val result = render(parsed, mode, rules, killSwitch, excludedRoutes)
        validate(result)
        return Plan(result, selectedApps, allowApps, warning)
    }

    // The config with this mode, these exclusions and the kill switch, and Settings' Excluded routes after its own.
    private fun render(parsed: TomlTable, mode: String, rules: List<String>, killSwitch: Boolean, excludedRoutes: List<String>): String {
        val root = linkedMapOf<String, Any>()
        parsed.keySet().forEach { root[it] = parsed.get(listOf(it))!! }
        root["vpn_mode"] = mode
        root["exclusions"] = rules
        root["killswitch_enabled"] = killSwitch
        // The config's own excluded routes, and Settings' after them (validate() has seen that both exist).
        val tun = parsed.getTable("listener.tun")!!
        val ownExcluded = tun.getArray("excluded_routes")!!.let { a -> (0 until a.size()).map { a.getString(it) } }
        val ipv6 = (parsed.getTable("endpoint")!!.getBoolean("has_ipv6") ?: false) &&
            tun.getArray("included_routes")!!.let { a -> (0 until a.size()).any { ':' in a.getString(it) } }
        val excluded = ExcludedRoutes.forPlan(ownExcluded, excludedRoutes, ipv6)
        // ktoml 0.7.0 cannot reliably decode escaped strings in inline tables.
        // Keep configuration tables as ordinary headers and scalar assignments.
        // A config saved by an earlier version can still hold a subscription address;
        // the core does nothing with it, so it is not handed the password in it.
        return document(root, subscriptionKeys, mapOf(listOf("listener", "tun", "excluded_routes") to excluded))
    }

    /**
     * The plan under geo routing, as the desktop routes it (BackendSplit.cpp, applySplitRules). The mode's lists decide the
     * core's mode: the blocked lists go through the tunnel (selective), the Russian ones around it (general). The user's own
     * rules keep the meaning the Split page gives them, its mode included:
     *  - pointing the lists' way, they are more of the list;
     *  - pointing the other way, they take what they name off the list (an entry of it written the same way, as "ozon.ru"
     *    and "*.ozon.ru"), and need nothing more: what no list names already goes their way;
     *  - applications to bypass the VPN are left out of it whatever the mode, as they always are. Applications to go
     *    through it cannot be: Android's allowlist would send everything else around the tunnel, lists or not. They are not
     *    applied, and the session says so.
     * Until the mode's lists are in, nothing is routed by a list: the full tunnel, with the user's own exceptions to it.
     */
    private fun prepareGeo(parsed: TomlTable, geo: Geo, enabled: Boolean, through: Boolean, domains: List<String>,
                           apps: List<String>, killSwitch: Boolean, excludedRoutes: List<String>): Plan {
        val ready = !geo.entries.isNullOrEmpty()
        val geoSelective = ready && geo.mode == GeoRouting.Mode.BLOCKED
        val mode = if (geoSelective) "selective" else "general"
        var user = if (enabled) SplitRules.normalize(domains.joinToString("\n")).map { SplitRules.core(it)!! }.distinct() else emptyList()
        var entries = geo.entries.orEmpty()
        if (through != geoSelective) {
            if (ready && user.isNotEmpty()) entries = GeoLists.without(entries, user)
            user = emptyList()
        }
        // The config's own exclusions count where they mean what they say: in the mode they were written for.
        val original = parsed.getArray("exclusions")?.let { a -> (0 until a.size()).map { a.getString(it) } }.orEmpty()
        val rules = ((if ((parsed.getString("vpn_mode") ?: "general") == mode) original else emptyList()) + user).distinct()
        val selected = if (enabled) apps.distinct().sorted() else emptyList()
        var warning: Int? = null
        val bypassApps = if (enabled && !through) selected else emptyList()
        if (enabled && through && selected.isNotEmpty() && ready) warning = R.string.text_through_vpn_apps_are_not_applied_under_geo_routing
        val checked = render(parsed, mode, rules, killSwitch, excludedRoutes)
        validate(checked)
        if (!ready) return Plan(checked, bypassApps, false, warning)
        val all = (rules + GeoLists.lines(entries)).distinct()
        // The lists are refused anything wider than a /8 or a /16 that would take traffic out of the tunnel; together
        // with the user's own rules they must not take a whole address family out either.
        if (mode == "general") require(!SplitRules.coversAddressFamily(all.filter { '/' in it } + platformExcludedRoutes)) {
            "Destination exclusions cover an entire address family"
        }
        return Plan(checked, bypassApps, false, warning, render(parsed, mode, all, killSwitch, excludedRoutes))
    }
}
