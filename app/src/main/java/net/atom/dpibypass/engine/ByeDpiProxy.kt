package net.atom.dpibypass.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ByeDPI (ciadpi) SOCKS5 proxy motoru için JNI köprüsü.
 *
 * Karar/gerekçe: Bu uygulamada tüm stratejiler (gömülü presetler, otomatik test
 * ve "Gelişmiş" serbest metin) ByeDPI **komut satırı argümanları** olarak ifade
 * edilir. Bu yüzden sadece komut-satırı yolunu (`jniCreateSocketWithCommandLine`)
 * kullanıyoruz; bu, tek bir kod yolu ile hem presetleri hem kullanıcı argümanlarını
 * destekler ve UI-parametre eşlemesi ile parametre kaymasını önler.
 *
 * ByeDPI native durumu tekildir (global `params`); aynı anda yalnızca tek instance
 * çalışabilir. Otomatik test motoru bu yüzden stratejileri **sırayla** dener.
 */
class ByeDpiProxy {
    companion object {
        init {
            System.loadLibrary("byedpi")
        }
    }

    private val mutex = Mutex()
    private var fd = -1
    // Olay döngüsü kendiliğinden bitti (ör. tanıtıcı sınırı); dinleme soketi
    // native tarafta zaten kapandı.
    private var loopEnded = false

    /**
     * Verilen ByeDPI argümanlarıyla dinleme soketini kurar ve olay döngüsünü
     * başlatır. Bu çağrı proxy durana kadar bloke eder (IO dispatcher'da çağır).
     *
     * @param args "ciadpi" + argümanlar (ör. ["ciadpi", "-p", "1080", "--split", "1"])
     * @return 0 başarı; <0 hata kodu
     */
    suspend fun startProxy(args: Array<String>): Int {
        val socketFd = createSocket(args)
        if (socketFd < 0) {
            return -1
        }
        val code = jniStartProxy(socketFd)
        // stopProxy ile durdurulduysa fd çoktan sıfırlanmıştır; değilse döngü
        // kendi kendine bitmiştir ve o fd numarası artık bize ait değildir.
        mutex.withLock { if (fd == socketFd) loopEnded = true }
        return code
    }

    /** Çalışan proxy'yi durdurur (dinleme soketini kapatır, native paramları sıfırlar). */
    suspend fun stopProxy(): Int = mutex.withLock {
        if (fd < 0) {
            throw IllegalStateException("Proxy çalışmıyor")
        }
        val result = jniStopProxy(if (loopEnded) -1 else fd)
        if (result == 0) {
            fd = -1
            loopEnded = false
        }
        result
    }

    private suspend fun createSocket(args: Array<String>): Int = mutex.withLock {
        if (fd >= 0) {
            throw IllegalStateException("Proxy zaten çalışıyor")
        }
        val newFd = jniCreateSocketWithCommandLine(args)
        if (newFd < 0) {
            return@withLock -1
        }
        fd = newFd
        newFd
    }

    private external fun jniCreateSocketWithCommandLine(args: Array<String>): Int
    private external fun jniStartProxy(fd: Int): Int
    private external fun jniStopProxy(fd: Int): Int
}
