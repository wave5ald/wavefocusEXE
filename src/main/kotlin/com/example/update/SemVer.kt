package com.example.update

/**
 * A small, dependency-free Semantic Versioning 2.0.0 parser/comparator.
 *
 * Supports `1.2.3`, `v1.2.3`, pre-releases (`2.0.0-beta.1`) and build
 * metadata (`2.0.0+build.5`, ignored for precedence).
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }

        // A version without a pre-release has higher precedence.
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1
        if (other.preRelease.isEmpty()) return -1

        val size = minOf(preRelease.size, other.preRelease.size)
        for (index in 0 until size) {
            val left = preRelease[index]
            val right = other.preRelease[index]
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val result = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1 // numeric identifiers are lower than alphanumeric ones
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String = buildString {
        append(major).append('.').append(minor).append('.').append(patch)
        if (preRelease.isNotEmpty()) append('-').append(preRelease.joinToString("."))
    }

    companion object {
        private val PATTERN = Regex(
            "^v?(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$"
        )

        /** Returns null when [raw] is not a valid semantic version. */
        fun parse(raw: String?): SemVer? {
            val value = normalize(raw)
            if (value.isEmpty()) return null
            val match = PATTERN.matchEntire(value) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return SemVer(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
                preRelease = pre.takeIf { it.isNotEmpty() }?.split('.') ?: emptyList(),
            )
        }

        /** True when [remote] is a strictly newer release than [current]. */
        fun isNewer(remote: String?, current: String?): Boolean {
            val newer = parse(remote) ?: return false
            val older = parse(current) ?: return false
            return newer > older
        }

        /** Pads `2.1` / `2` to full `major.minor.patch` form. */
        fun normalize(raw: String?): String {
            var value = raw?.trim().orEmpty()
            if (value.isEmpty()) return value
            if (value.startsWith("v") || value.startsWith("V")) value = value.substring(1)
            val suffixIndex = value.indexOfFirst { it == '-' || it == '+' }
            val core = if (suffixIndex >= 0) value.substring(0, suffixIndex) else value
            val suffix = if (suffixIndex >= 0) value.substring(suffixIndex) else ""
            val parts = core.split('.')
            if (parts.size !in 1..3 || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) {
                return value
            }
            val padded = when (parts.size) {
                1 -> "${parts[0]}.0.0"
                2 -> "${parts[0]}.${parts[1]}.0"
                else -> core
            }
            return padded + suffix
        }
    }
}
