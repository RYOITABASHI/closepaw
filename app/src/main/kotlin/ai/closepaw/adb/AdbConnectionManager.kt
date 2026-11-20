package ai.closepaw.adb

import ai.closepaw.browser.cdp.wireless.WirelessAdbProviders
import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** App-private RSA identity for the local wireless debugging daemon. */
internal class AdbConnectionManager private constructor(
    private val storedKey: ByteArray,
    private val storedCert: Certificate,
) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
        setHostAddress(LOCALHOST)
    }

    // libadb can destroy the key object when it disconnects. Give each connection its own copy.
    override fun getPrivateKey(): PrivateKey = decodeKey(storedKey)

    override fun getCertificate(): Certificate = storedCert

    override fun getDeviceName(): String = DEVICE_NAME

    companion object {
        const val LOCALHOST = "127.0.0.1"
        // Keep the spike filenames so existing wireless-debugging pairing survives.
        const val KEY_FILE = "closepaw-spike-adb.key.pk8"
        const val CERT_FILE = "closepaw-spike-adb.crt.der"
        private const val DEVICE_NAME = "closepaw-spike"
        private val lock = Any()

        /** Loads the stored identity, or generates and stores a new one. Call off the main thread. */
        fun create(ctx: Context): AdbConnectionManager = synchronized(lock) {
            WirelessAdbProviders.ensure() // registers the bundled BouncyCastle (+ Conscrypt) providers
            val dir = ctx.applicationContext.filesDir
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)
            load(keyFile, certFile)?.let { (k, c) -> return AdbConnectionManager(k.encoded, c) }
            check(!keyFile.exists()) { "Stored ADB identity could not be loaded" }
            val (k, c) = generate()
            // Cert first, key last so an interrupted initial write can be retried.
            writeAtomic(certFile, c.encoded)
            writeAtomic(keyFile, k.encoded)
            AdbConnectionManager(k.encoded, c)
        }

        private fun load(keyFile: File, certFile: File): Pair<PrivateKey, Certificate>? {
            if (!keyFile.isFile || !certFile.isFile) return null
            return runCatching {
                val key = decodeKey(keyFile.readBytes())
                val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                key to cert
            }.getOrNull()
        }

        private fun decodeKey(bytes: ByteArray): PrivateKey =
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))

        private fun generate(): Pair<PrivateKey, Certificate> {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 60_000L)
            // adbd only checks the public key; a long validity avoids silent expiry of the pairing.
            val notAfter = Date(now + 30L * 365L * 24L * 60L * 60L * 1000L)
            val serial = BigInteger(64, SecureRandom()).abs().let { if (it.signum() == 0) BigInteger.ONE else it }
            val name = X500Principal("CN=$DEVICE_NAME")
            val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, keyPair.public)
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            val signer = JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.private)
            val cert = JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer))
            return keyPair.private to cert
        }

        private fun writeAtomic(dest: File, bytes: ByteArray) {
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            tmp.writeBytes(bytes)
            check(tmp.renameTo(dest)) { "Could not persist ADB identity" }
        }
    }
}
