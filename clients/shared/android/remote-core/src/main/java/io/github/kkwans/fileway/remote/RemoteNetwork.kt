package io.github.kkwans.fileway.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Proxy
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okhttp3.OkHttpClient

fun lanNetwork(context: Context): Network? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let { caps ->
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } == true
    }
}

fun lanAddress(context: Context, network: Network): String? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
        .mapNotNull { it.address.hostAddress }.filter { localAddress(it) != null }
    return addresses.firstOrNull { ':' !in it } ?: addresses.firstOrNull { !it.startsWith("fe80:", ignoreCase = true) }
}

/** Trust one QR-verified installation key on this client only. Default hostname
 * verification remains enabled against its stable certificate DNS name.
 * A public-key pin alone would not make a self-signed cert a system trust root. */
internal fun pinnedClient(context: Context, descriptor: TvDescriptor): OkHttpClient {
    val device = TvDescriptor.from(descriptor.json())
    val expected = Base64.getUrlDecoder().decode(device.fingerprint)
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("Client certificates are not accepted")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain == null || chain.size != 1) throw CertificateException("Unexpected TV certificate chain")
            val leaf = chain[0]
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
            if (!MessageDigest.isEqual(actual, expected)) throw CertificateException("TV installation identity changed")
            leaf.checkValidity()
            leaf.verify(leaf.publicKey)
            if (leaf.basicConstraints >= 0 || leaf.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") != true)
                throw CertificateException("Invalid TV server certificate purpose")
        }
    }
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    return OkHttpClient.Builder()
        .dns(object:Dns {
            override fun lookup(hostname:String):List<InetAddress>{
                if (hostname != device.serverName) throw UnknownHostException("Only the selected TV is permitted")
                return listOf(requireNotNull(localAddress(device.address)))
            }
        })
        .apply { lanNetwork(context)?.let { socketFactory(it.socketFactory) } }
        .sslSocketFactory(tls.socketFactory, trust)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).build()
}
