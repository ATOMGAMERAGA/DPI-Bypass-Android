package net.atom.dpibypass.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ByeDPI'ın native durumu süreç genelinde TEKİLDİR (global `params`, tek dinleyici).
 * Ayrı [ByeDpiProxy] nesneleri bağımsız motor örnekleri DEĞİLDİR: tünel açıkken
 * strateji testinin ikinci bir örnek başlatması çalışan tüneli bozar.
 *
 * Bu nesne motorun kimde olduğunu tek yerde tutar:
 *  - [Owner.VPN]  — tünel servisi (Sosyal Erişim denemeleri de bu sahipliği ödünç alır)
 *  - [Owner.STRATEGY_TEST] — tünel kapalıyken tanı ekranındaki strateji testi
 *
 * Kullanıcının "Bağlan" komutu önceliklidir: VPN sahipliği istediğinde süren testi
 * iptal ettirir ve testin kaynaklarını bırakmasını bekler. Test, VPN sahipken
 * başlatılamaz. Saf Kotlin; birim testinde doğrudan sınanır.
 */
object EngineOwnership {
    enum class Owner { VPN, STRATEGY_TEST }

    private class Lease(val owner: Owner, val token: Long, val preempt: () -> Unit) {
        val released = CompletableDeferred<Unit>()
    }

    private var lease: Lease? = null
    private var nextToken = 1L

    @Synchronized fun owner(): Owner? = lease?.owner

    /**
     * Motor boşsa [owner] adına alır ve bir jeton döner; doluysa null. Aynı sahibin
     * ikinci isteği de reddedilir (komutlar idempotent kalsın diye çağıran
     * [owner] ile kontrol eder).
     */
    @Synchronized fun tryAcquire(owner: Owner, preempt: () -> Unit = {}): Long? {
        if (lease != null) return null
        return Lease(owner, nextToken++, preempt).also { lease = it }.token
    }

    /**
     * VPN için alır. Başka bir sahip (test) varsa önce iptal ettirir ve en fazla
     * [timeoutMs] bırakmasını bekler. Bekleme dolarsa null döner: iki motor aynı
     * anda çalıştırılmaz.
     */
    suspend fun acquireForVpn(timeoutMs: Long = 10_000): Long? {
        while (true) {
            val current = synchronized(this) {
                val held = lease
                if (held == null) return Lease(Owner.VPN, nextToken++) {}.also { lease = it }.token
                held
            }
            // Servis tek örneklidir: yeni bir servis örneği VPN sahipliği istiyorsa
            // eskisi artık yoktur (ör. sistem servisi yeniden başlattı); devralınır.
            if (current.owner == Owner.VPN) synchronized(this) {
                if (lease === current) return Lease(Owner.VPN, nextToken++) {}.also { lease = it }.token
            }
            if (current.owner == Owner.VPN) continue
            current.preempt()
            withTimeoutOrNull(timeoutMs) { current.released.await() } ?: return null
        }
    }

    /** Yalnızca jetonun sahibi bırakabilir; eski bir jeton yeni sahipliği bırakamaz. */
    fun release(token: Long) {
        val released = synchronized(this) {
            val held = lease ?: return
            if (held.token != token) return
            lease = null
            held
        }
        released.released.complete(Unit)
    }

    /** Yalnızca testler için. */
    @Synchronized internal fun resetForTest() { lease?.released?.complete(Unit); lease = null }
}
