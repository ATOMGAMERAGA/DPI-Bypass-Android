package net.atom.dpibypass.data

/** Bağlantı yaşam döngüsü durumu. UI ve Tile bu duruma göre çizilir. */
enum class ConnectionState {
    Disconnected,
    Testing,     // otomatik strateji testi sürüyor
    Connecting,
    Connected,
    Failed,
}

/** Uygulama modu: otomatik (uygulama en iyi stratejiyi bulur) veya manuel. */
enum class OperationMode {
    Auto,
    Manual;

    companion object {
        fun fromName(name: String?): OperationMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Auto
    }
}

/** Uygulama ayırma (split tunneling) modu. */
enum class AppFilterMode {
    All,       // tüm uygulamalar tünelde
    Include,   // yalnızca seçili uygulamalar
    Exclude;   // seçili uygulamalar hariç

    companion object {
        fun fromName(name: String?): AppFilterMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: All
    }
}

/** Tema tercihi. */
enum class ThemePref {
    System,
    Light,
    Dark;

    companion object {
        fun fromName(name: String?): ThemePref =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: System
    }
}

/**
 * Aktif bağlantının özeti — bildirimde, ana ekranda ve tile alt metninde gösterilir.
 * Örn: "Superonline · P2 · 34 ms".
 */
data class ActiveProfile(
    val strategyId: String,
    val strategyName: String,
    val ispName: String,
    /** Bağlanırken ölçülen en yavaş hedefin TLS el sıkışması; uygulamanın ping'i değildir. */
    val latencyMs: Int?,
    /** "x/y hedef" kapsama özeti; çözülemeyen zorunlu hedefler paydada kalır. */
    val coverage: String? = null,
    /** Motor tabanı Sosyal Erişim'de onaylanan planla değiştirildi mi? */
    val socialPlan: Boolean = false,
) {
    fun shortLabel(): String = buildString {
        append(ispName)
        append(" · ")
        append(strategyId)
        latencyMs?.let { append(" · TLS ${it} ms") }
    }
}
