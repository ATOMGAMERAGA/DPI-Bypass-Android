package net.atom.dpibypass.dns

// ---------------------------------------------------------------------------
// SEÇİLEN DNS'İN GERÇEKTEN ETKİ ETMESİ
//
// Ayarlardaki DNS seçimi iki ayrı yere gider ve ikisi de burada tek bir plana
// bağlanır:
//
//   * DoH URL'i — uygulamanın KENDİ sorguları (strateji testi, sağlık kontrolü)
//     bunu kullanır; HTTPS üzerinden, IP'ye doğrudan bağlanarak.
//   * Düz DNS adresleri — tünel kurulurken cihazdaki UYGULAMALARA bunlar verilir
//     (`VpnService.Builder.addDnsServer`). Kullanıcının "DNS'i değiştirdim ama bir
//     şey değişmedi" dediği yer burasıdır.
//
// Eski davranışta ikinci liste, DoH URL'inin içinden ayrıştırılıyordu ve URL'de
// IP yerine ana bilgisayar adı geçtiği anda SESSİZCE 1.1.1.1'e düşülüyordu.
// Özel alana düz bir IP ("9.9.9.9") yazmak ise büsbütün bozuyordu: o metin DoH
// URL'i olarak kullanılıyor, hiçbir sorgu çözülemiyordu. Artık:
//
//   1. Özel alan üç biçimi de kabul eder — tam DoH URL'i, çıplak ana bilgisayar
//      adı, ya da bir/birkaç düz IP.
//   2. Ana bilgisayar adı verildiyse IP'si bağlanma anında, seçili sağlayıcının
//      DoH'u üzerinden çözülür (bootstrap) — sistem DNS'ine güvenilmez.
//   3. Hiçbir şey çözülemezse bu SESSİZCE olmaz: plan, ne olduğunu anlatan bir
//      etiketle döner ve arayüz bunu gösterir.
// ---------------------------------------------------------------------------

/** Bağlantı sırasında gerçekten uygulanacak DNS yapılandırması. */
data class DnsPlan(
    /** Uygulamanın kendi çözümlemeleri için DoH endpoint'i. */
    val dohUrl: String,
    /** Cihazdaki uygulamalara verilecek düz DNS adresleri (sırayla denenir). */
    val servers: List<String>,
    /** Arayüzde gösterilecek insan-okur özet. */
    val label: String,
    /** Kullanıcının yazdığı özel adres mi kullanılıyor? */
    val custom: Boolean,
    /** Özel adres istendi ama uygulanamadıysa sebebi; aksi hâlde null. */
    val warning: String? = null,
    /**
     * Ağ erişimi olmadan hesaplanan planlarda (arayüz önizlemesi), IP'si henüz
     * çözülmemiş ana bilgisayar adı. Bu bir hata değildir: çözümleme bağlanma
     * anında yapılır. [warning] ile karıştırılmamalı — biri "olmadı", bu "henüz
     * bakılmadı" demektir.
     */
    val pendingHost: String? = null,
)

/** Özel DNS alanına yazılanın, ağa çıkmadan anlaşılabilen hâli. */
sealed interface CustomDns {
    /** Adresler doğrudan IP olarak verilmiş — çözümlemeye gerek yok. */
    data class Literal(val dohUrl: String, val servers: List<String>) : CustomDns

    /** Ana bilgisayar adı verilmiş — IP'si bağlanırken çözülecek. */
    data class Named(val dohUrl: String, val host: String) : CustomDns

    /** Anlaşılamayan metin. */
    data class Invalid(val reason: String) : CustomDns
}

object DnsPlanner {

    /** Varsayılan DoH yolu; kullanıcı yalnızca adres yazdığında eklenir. */
    private const val DEFAULT_PATH = "/dns-query"

    /** Uygulamalara en fazla bu kadar DNS adresi verilir. */
    private const val MAX_SERVERS = 2

    private val HOSTNAME = Regex(
        "^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$",
    )

    /**
     * Özel alandaki metni çözümler. Boşsa null döner (sağlayıcı seçimi geçerlidir).
     * Ağ erişimi YAPMAZ — arayüz bunu yazarken anlık geri bildirim için çağırır.
     */
    fun parseCustom(raw: String): CustomDns? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 1) Yalnızca IP(ler): "9.9.9.9" ya da "9.9.9.9, 149.112.112.112"
        val tokens = text.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isNotEmpty() && tokens.all { isIpLiteral(it) }) {
            val servers = tokens.distinct().take(MAX_SERVERS)
            return CustomDns.Literal(
                dohUrl = "https://${bracketIfIpv6(servers.first())}$DEFAULT_PATH",
                servers = servers,
            )
        }

        // 2) URL ya da çıplak ana bilgisayar adı. Birden fazla parça yazılmışsa
        //    ilki esas alınır; karışık girdide sessizce yanlış olanı seçmemek için
        //    fazlası yok sayılır.
        val first = tokens.first()
        val withScheme = if (first.contains("://")) first else "https://$first"
        val schemeEnd = withScheme.indexOf("://")
        if (withScheme.take(schemeEnd).lowercase() != "https") {
            return CustomDns.Invalid("DoH adresi https:// ile başlamalı")
        }

        val rest = withScheme.substring(schemeEnd + 3)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty()) return CustomDns.Invalid("Adres eksik")
        // Kullanıcı adı/parola ya da port yazılmışsa ana bilgisayarı ayıkla.
        val hostPart = authority.substringAfterLast('@')
        val host = when {
            hostPart.startsWith("[") -> hostPart.substringAfter('[').substringBefore(']')
            hostPart.count { it == ':' } == 1 -> hostPart.substringBefore(':')
            else -> hostPart
        }
        if (host.isEmpty()) return CustomDns.Invalid("Adres eksik")

        // Yol yazılmadıysa sağlayıcıların ortak yolu eklenir; "…/dns-query"
        // olmadan çoğu DoH uç noktası 404 döner.
        val tail = rest.removePrefix(authority)
        val url = "https://" + authority + if (tail.startsWith("/")) tail else DEFAULT_PATH + tail

        return when {
            isIpLiteral(host) -> CustomDns.Literal(url, listOf(host))
            HOSTNAME.matches(host) -> CustomDns.Named(url, host)
            else -> CustomDns.Invalid("Geçersiz adres")
        }
    }

    /**
     * Etkin DoH endpoint'i — ağ gerektirmez. Uygulamanın kendi çözümleyicisi
     * (strateji testi, sağlık kontrolü) bunu kullanır.
     */
    fun dohUrl(provider: DohProvider, customRaw: String): String =
        when (val custom = parseCustom(customRaw)) {
            null, is CustomDns.Invalid -> provider.url
            is CustomDns.Literal -> custom.dohUrl
            is CustomDns.Named -> custom.dohUrl
        }

    /**
     * Uygulanacak tam plan.
     *
     * [resolveHost] ana bilgisayar adını IP'ye çevirir; sistem DNS'i ele
     * geçirilmiş olabileceği için bunun DoH üzerinden yapılması beklenir. Ağa
     * çıkamayan çağıranlar (arayüz önizlemesi) bunu null bırakır — o zaman ad
     * "çözülemedi" değil "henüz çözülmedi" sayılır ve plan [DnsPlan.pendingHost]
     * ile döner.
     */
    fun plan(
        provider: DohProvider,
        customRaw: String,
        resolveHost: ((host: String, bootstrapDohUrl: String) -> List<String>)? = null,
    ): DnsPlan {
        val fallback = DnsPlan(
            dohUrl = provider.url,
            servers = provider.dnsServers,
            label = provider.displayName,
            custom = false,
        )

        return when (val custom = parseCustom(customRaw)) {
            null -> fallback

            is CustomDns.Invalid -> fallback.copy(
                warning = "${custom.reason} — ${provider.displayName} kullanılıyor.",
            )

            is CustomDns.Literal -> DnsPlan(
                dohUrl = custom.dohUrl,
                servers = custom.servers,
                label = "Özel · ${custom.servers.joinToString(", ")}",
                custom = true,
            )

            is CustomDns.Named -> if (resolveHost == null) {
                // Önizleme: ad çözümü ağ ister, burada yapılamaz. Gerçek adresler
                // bağlanma anında belli olur.
                fallback.copy(
                    label = "Özel · ${custom.host}",
                    pendingHost = custom.host,
                )
            } else {
                val ips = runCatching { resolveHost(custom.host, provider.url) }
                    .getOrDefault(emptyList())
                    .filter { isIpLiteral(it) }
                    .distinct()
                    .take(MAX_SERVERS)
                if (ips.isNotEmpty()) {
                    DnsPlan(
                        dohUrl = custom.dohUrl,
                        servers = ips,
                        label = "Özel · ${custom.host} (${ips.joinToString(", ")})",
                        custom = true,
                    )
                } else {
                    // Ad çözülemedi: uygulamalara verecek IP yok. Sağlayıcıya
                    // dönülür ama bu kez sessizce değil.
                    fallback.copy(
                        warning = "${custom.host} adresinin IP'si çözülemedi — " +
                            "${provider.displayName} kullanılıyor.",
                    )
                }
            }
        }
    }

    /** IPv4/IPv6 metin gösterimi mi? (Birim testlerde çalışsın diye saf Kotlin.) */
    fun isIpLiteral(value: String): Boolean = isIpv4(value) || isIpv6(value)

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() <= 255
        }
    }

    private fun isIpv6(value: String): Boolean {
        if (!value.contains(':')) return false
        if (value.count { it == ':' } > 8) return false
        return value.all { it.isDigit() || it in "abcdefABCDEF:." }
    }

    private fun bracketIfIpv6(ip: String): String = if (ip.contains(':')) "[$ip]" else ip
}
