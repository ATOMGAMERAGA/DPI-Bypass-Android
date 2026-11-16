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
    // Bu nesne tek kullanımlıktır. Durdurma, kurulumdan ÖNCE gelirse (ör. strateji
    // testi dinleyiciyi beklerken zaman aşımına uğradı) kurulum hiç yapılmaz;
    // eskiden durdurma "çalışmıyor" diye reddediliyor, sonra kurulan döngü
    // kimse durdurmadığı için sonsuza dek bekliyordu (test ekranı donuyordu).
    private var stopRequested = false

    /**
     * Verilen ByeDPI argümanlarıyla dinleme soketini kurar ve olay döngüsünü
     * başlatır. Bu çağrı proxy durana kadar bloke eder (IO dispatcher'da çağır).
     * Döndüğünde native parametreler sıfırlanmıştır; aynı süreçte yeni bir motor
     * güvenle kurulabilir.
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
        // Döngü dinleme soketini kendisi kapattı; numara artık bize ait değil.
        mutex.withLock { fd = -1 }
        return code
    }

    /**
     * Çalışan proxy'yi durdurur: dinleme soketi kapatılır, olay döngüsü kendi
     * iş parçacığında biter ve parametreleri orada sıfırlar. Çalışmıyorsa (henüz
     * kurulmadı ya da çoktan bitti) bir şey yapmaz. Döngünün bitişini beklemek
     * çağıranın işidir ([startProxy]'yi çalıştıran işi `join` ederek).
     */
    suspend fun stopProxy(): Int = mutex.withLock {
        stopRequested = true
        if (fd < 0) 0 else jniStopProxy(fd)
    }

    private suspend fun createSocket(args: Array<String>): Int = mutex.withLock {
        if (stopRequested) {
            return@withLock -1
        }
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
