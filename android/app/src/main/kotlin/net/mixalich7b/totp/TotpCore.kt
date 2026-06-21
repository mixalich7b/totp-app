package net.mixalich7b.totp

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class TotpAlgorithm(val wireValue: Int, val jcaName: String) {
    SHA1(1, "HmacSHA1"),
    SHA256(2, "HmacSHA256");

    companion object {
        fun fromName(value: String): TotpAlgorithm = when (value.uppercase(Locale.ROOT).replace("-", "")) {
            "SHA1" -> SHA1
            "SHA256" -> SHA256
            else -> throw IllegalArgumentException("Поддерживаются только SHA1 и SHA256")
        }

        fun fromWire(value: Int): TotpAlgorithm = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Неподдерживаемый TOTP-алгоритм")
    }
}

data class TotpEntry(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val issuer: String,
    val accountName: String,
    val secret: ByteArray,
    val algorithm: TotpAlgorithm = TotpAlgorithm.SHA1,
    val digits: Int = 6,
    val periodSeconds: Int = 30,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val revision: Long = 0,
) {
    init {
        require(displayName.isNotBlank()) { "Название обязательно" }
        require(secret.isNotEmpty()) { "Секрет пуст" }
        require(digits == 6 || digits == 8) { "Допустимо 6 или 8 цифр" }
        require(periodSeconds in 5..300) { "Период должен быть от 5 до 300 секунд" }
    }

    override fun equals(other: Any?): Boolean = other is TotpEntry &&
        id == other.id && displayName == other.displayName && issuer == other.issuer &&
        accountName == other.accountName && secret.contentEquals(other.secret) &&
        algorithm == other.algorithm && digits == other.digits && periodSeconds == other.periodSeconds &&
        createdAt == other.createdAt && updatedAt == other.updatedAt && revision == other.revision

    override fun hashCode(): Int = id.hashCode()
}

object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(input: String): ByteArray {
        val compact = input.uppercase(Locale.ROOT).filterNot { it.isWhitespace() || it == '-' }
        require(compact.isNotEmpty()) { "Секрет пуст" }
        val paddingStart = compact.indexOf('=')
        val normalized = if (paddingStart >= 0) compact.substring(0, paddingStart) else compact
        require(normalized.isNotEmpty()) { "Секрет пуст" }
        val remainder = normalized.length % 8
        val expectedPadding = when (remainder) {
            0 -> 0
            2 -> 6
            4 -> 4
            5 -> 3
            7 -> 1
            else -> throw IllegalArgumentException("Некорректная длина Base32")
        }
        if (paddingStart >= 0) {
            val padding = compact.substring(paddingStart)
            require(padding.all { it == '=' } && compact.length % 8 == 0 && padding.length == expectedPadding) {
                "Некорректный padding Base32"
            }
        }
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>((normalized.length * 5) / 8)
        for (character in normalized) {
            val value = ALPHABET.indexOf(character)
            require(value >= 0) { "Недопустимый символ Base32" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xff).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        require(bits == 0 || buffer == 0) { "Некорректный хвост Base32" }
        return out.toByteArray()
    }
}

object Totp {
    fun generate(entry: TotpEntry, unixSeconds: Long = System.currentTimeMillis() / 1000): String {
        val counter = unixSeconds / entry.periodSeconds
        val message = ByteArray(8) { index -> (counter ushr (56 - index * 8)).toByte() }
        val mac = Mac.getInstance(entry.algorithm.jcaName)
        mac.init(SecretKeySpec(entry.secret, entry.algorithm.jcaName))
        val digest = mac.doFinal(message)
        val offset = digest.last().toInt() and 0x0f
        val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
        val modulus = if (entry.digits == 8) 100_000_000 else 1_000_000
        return (binary % modulus).toString().padStart(entry.digits, '0')
    }
}

object OtpAuthParser {
    fun parse(raw: String): TotpEntry {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("otpauth", true) && uri.host.equals("totp", true)) {
            "Ожидался otpauth://totp URI"
        }
        val label = decode(uri.rawPath.orEmpty().removePrefix("/"))
        val query = parseQuery(uri.rawQuery.orEmpty())
        val issuerFromLabel = label.substringBefore(':', "").trim()
        val account = label.substringAfter(':', label).trim()
        val issuerFromQuery = query["issuer"].orEmpty().trim()
        require(issuerFromLabel.isBlank() || issuerFromQuery.isBlank() || issuerFromLabel == issuerFromQuery) {
            "Issuer в label и query не совпадает"
        }
        val issuer = issuerFromQuery.ifBlank { issuerFromLabel }
        val algorithm = TotpAlgorithm.fromName(query["algorithm"] ?: "SHA1")
        val digits = (query["digits"] ?: "6").toIntOrNull()
            ?: throw IllegalArgumentException("Некорректное digits")
        val period = (query["period"] ?: "30").toIntOrNull()
            ?: throw IllegalArgumentException("Некорректный period")
        require(digits == 6 || digits == 8) { "Допустимо 6 или 8 цифр" }
        require(period in 5..300) { "Период должен быть от 5 до 300 секунд" }
        val secret = Base32.decode(requireNotNull(query["secret"]) { "В URI отсутствует secret" })
        val displayName = listOf(issuer, account).filter { it.isNotBlank() }.joinToString(": ")
            .ifBlank { label.ifBlank { "TOTP" } }
        return TotpEntry(
            displayName = displayName,
            issuer = issuer,
            accountName = account,
            secret = secret,
            algorithm = algorithm,
            digits = digits,
            periodSeconds = period,
        )
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&')
        .filter { it.isNotEmpty() }
        .associate { part ->
            val pieces = part.split('=', limit = 2)
            decode(pieces[0]).lowercase(Locale.ROOT) to decode(pieces.getOrElse(1) { "" })
        }

    // URI percent-decoding is performed once. A literal '+' is not a form-encoded space.
    private fun decode(value: String): String = URLDecoder.decode(
        value.replace("+", "%2B"),
        StandardCharsets.UTF_8.name(),
    )
}
