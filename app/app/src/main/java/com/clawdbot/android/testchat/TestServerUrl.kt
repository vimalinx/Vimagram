package com.clawdbot.android.testchat

private const val officialHost = "vimagram.vimalinx.xyz"
private const val legacyHost = "123.60.21.129:8788"

fun normalizeServerBaseUrl(raw: String): String {
  val trimmed = raw.trim().removeSuffix("/")
  if (trimmed.isBlank()) return trimmed
  val normalized = normalizeOfficialHost(trimmed)
  if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
    return normalized
  }
  val scheme = if (isLikelyLocalHost(normalized)) "http://" else "https://"
  return scheme + normalized
}

private fun normalizeOfficialHost(raw: String): String {
  val withoutScheme =
    raw.removePrefix("http://")
      .removePrefix("https://")
  if (
    withoutScheme.equals(officialHost, ignoreCase = true) ||
      withoutScheme.equals("$officialHost:8788", ignoreCase = true) ||
      withoutScheme.equals(legacyHost, ignoreCase = true)
  ) {
    return "https://$officialHost"
  }
  return raw
}

private fun isLikelyLocalHost(host: String): Boolean {
  val lowered = host.lowercase()
  val base = lowered.substringBefore("/").substringBefore(":")
  if (base == "localhost" || base == "127.0.0.1") return true
  if (base.startsWith("10.") || base.startsWith("192.168.")) return true
  if (base.startsWith("172.")) {
    val second = base.split(".").getOrNull(1)?.toIntOrNull()
    if (second != null && second in 16..31) return true
  }
  return false
}
