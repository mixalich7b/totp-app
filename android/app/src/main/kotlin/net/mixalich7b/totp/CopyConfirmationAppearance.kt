package net.mixalich7b.totp

internal data class CopyConfirmationAppearance(
    val backgroundArgb: Int,
    val textArgb: Int,
)

internal fun copyConfirmationAppearance() = CopyConfirmationAppearance(
    backgroundArgb = 0xff303030.toInt(),
    textArgb = 0xffffffff.toInt(),
)
