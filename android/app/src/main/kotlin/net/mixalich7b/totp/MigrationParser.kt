package net.mixalich7b.totp

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class MigrationBatch(
    val entries: List<TotpEntry>,
    val batchSize: Int,
    val batchIndex: Int,
    val batchId: Long,
)

object MigrationParser {
    fun parse(raw: String): MigrationBatch {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("otpauth-migration", true) && uri.host.equals("offline", true)) {
            "Ожидался Google Authenticator migration QR"
        }
        val encoded = queryParameter(uri.rawQuery.orEmpty(), "data")
            ?: throw IllegalArgumentException("В migration URI отсутствует data")
        val payload = decodeBase64(encoded)
        try {
            val root = ProtoReader(payload)
            val entries = mutableListOf<TotpEntry>()
            try {
                var version = 0L
                var batchSize = 1
                var batchIndex = 0
                var batchId = 0L
                while (root.hasRemaining()) {
                    val tag = root.readTag()
                    when (tag.fieldNumber) {
                        1 -> {
                            tag.requireWireType(2)
                            val entryBytes = root.readBytes()
                            try {
                                entries += parseEntry(entryBytes)
                            } finally {
                                entryBytes.fill(0)
                            }
                        }
                        2 -> {
                            tag.requireWireType(0)
                            version = root.readVarint()
                        }
                        3 -> {
                            tag.requireWireType(0)
                            batchSize = root.readVarint().toInt()
                        }
                        4 -> {
                            tag.requireWireType(0)
                            batchIndex = root.readVarint().toInt()
                        }
                        5 -> {
                            tag.requireWireType(0)
                            batchId = root.readVarint()
                        }
                        else -> root.skip(tag.wireType)
                    }
                }
                require(version in 0..2) { "Неподдерживаемая версия migration payload: $version" }
                require(entries.isNotEmpty()) { "В QR нет поддерживаемых TOTP записей" }
                require(batchSize in 1..100 && batchIndex in 0 until batchSize) {
                    "Некорректный migration batch"
                }
                return MigrationBatch(entries, batchSize, batchIndex, batchId)
            } catch (error: Exception) {
                entries.forEach { it.secret.fill(0) }
                throw error
            }
        } finally {
            payload.fill(0)
        }
    }

    private fun parseEntry(bytes: ByteArray): TotpEntry {
        val reader = ProtoReader(bytes)
        var secret = ByteArray(0)
        var name = ""
        var issuer = ""
        var algorithm = 1
        var digits = 1
        var type = 0
        try {
            while (reader.hasRemaining()) {
                val tag = reader.readTag()
                when (tag.fieldNumber) {
                    1 -> {
                        tag.requireWireType(2)
                        secret.fill(0)
                        secret = reader.readBytes()
                    }
                    2 -> {
                        tag.requireWireType(2)
                        name = reader.readString()
                    }
                    3 -> {
                        tag.requireWireType(2)
                        issuer = reader.readString()
                    }
                    4 -> {
                        tag.requireWireType(0)
                        algorithm = reader.readVarint().toInt()
                    }
                    5 -> {
                        tag.requireWireType(0)
                        digits = reader.readVarint().toInt()
                    }
                    6 -> {
                        tag.requireWireType(0)
                        type = reader.readVarint().toInt()
                    }
                    else -> reader.skip(tag.wireType)
                }
            }
            require(type == 0 || type == 2) { "HOTP не поддерживается" }
            val account = name.substringAfter(':', name).trim()
            val effectiveIssuer = issuer.ifBlank { name.substringBefore(':', "").trim() }
            return TotpEntry(
                displayName = listOf(effectiveIssuer, account).filter { it.isNotBlank() }.joinToString(": ")
                    .ifBlank { "TOTP" },
                issuer = effectiveIssuer,
                accountName = account,
                secret = secret,
                algorithm = TotpAlgorithm.fromWire(if (algorithm == 0) 1 else algorithm),
                digits = when (digits) {
                    0, 1 -> 6
                    2 -> 8
                    else -> throw IllegalArgumentException("Неподдерживаемое число цифр")
                },
            )
        } catch (error: Exception) {
            secret.fill(0)
            throw error
        }
    }

    private fun queryParameter(query: String, expectedName: String): String? = query.split('&')
        .asSequence()
        .filter { it.isNotEmpty() }
        .map { it.split('=', limit = 2) }
        .firstOrNull { decodeQueryPart(it[0]).equals(expectedName, true) }
        ?.let { decodeQueryPart(it.getOrElse(1) { "" }) }

    // Base64 uses '+' as data, not as the application/x-www-form-urlencoded space marker.
    private fun decodeQueryPart(value: String): String = URLDecoder.decode(
        value.replace("+", "%2B"),
        StandardCharsets.UTF_8.name(),
    )

    private fun decodeBase64(value: String): ByteArray {
        val normalized = value.trim()
        return try {
            Base64.getDecoder().decode(normalized)
        } catch (standardError: IllegalArgumentException) {
            try {
                Base64.getUrlDecoder().decode(normalized)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Некорректный Base64 в migration QR", standardError)
            }
        }
    }

    private data class ProtoTag(val fieldNumber: Int, val wireType: Int) {
        fun requireWireType(expected: Int) {
            require(wireType == expected) { "Некорректный wire type поля protobuf $fieldNumber" }
        }
    }

    private class ProtoReader(private val data: ByteArray) {
        private var position = 0
        fun hasRemaining() = position < data.size

        fun readTag(): ProtoTag {
            val tag = readVarint()
            require(tag in 1..Int.MAX_VALUE.toLong()) { "Некорректный protobuf tag" }
            return ProtoTag((tag ushr 3).toInt(), (tag and 7).toInt()).also {
                require(it.fieldNumber > 0) { "Некорректный номер поля protobuf" }
            }
        }

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                require(position < data.size) { "Обрезанный protobuf" }
                val byte = data[position++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Слишком длинный protobuf varint")
        }

        fun readBytes(): ByteArray {
            val rawLength = readVarint()
            require(rawLength <= Int.MAX_VALUE) { "Некорректная длина protobuf" }
            val length = rawLength.toInt()
            require(length <= data.size - position) { "Некорректная длина protobuf" }
            return data.copyOfRange(position, position + length).also { position += length }
        }

        fun readString() = readBytes().toString(Charsets.UTF_8)

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> skipBytes(8)
                2 -> {
                    val length = readVarint()
                    require(length <= Int.MAX_VALUE) { "Некорректная длина protobuf" }
                    skipBytes(length.toInt())
                }
                5 -> skipBytes(4)
                else -> throw IllegalArgumentException("Неподдерживаемый protobuf wire type")
            }
        }

        private fun skipBytes(length: Int) {
            require(length >= 0 && length <= data.size - position) { "Обрезанный protobuf" }
            position += length
        }
    }
}
