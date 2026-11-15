package net.atom.dpibypass.util

import okhttp3.OkHttpClient

/**
 * Süreç genelinde TEK OkHttp tabanı.
 *
 * Her `OkHttpClient.Builder().build()` kendi bağlantı havuzunu ve iş parçacığı
 * havuzunu kurar; boşta kalan bağlantılar ve iş parçacıkları dakikalarca yaşar.
 * DoH çözümleyici ve ISS tespiti eskiden her çağrıda yeni bir istemci kuruyordu:
 * strateji yarışında onlarca istemci, onlarca iş parçacığı ve her sorguda yeniden
 * TLS el sıkışması demekti. Türetilen istemciler ([OkHttpClient.newBuilder])
 * havuzu ve dağıtıcıyı paylaşır; yalnızca zaman aşımı gibi ayarları farklıdır.
 */
object Http {
    val base: OkHttpClient by lazy { OkHttpClient() }
}
