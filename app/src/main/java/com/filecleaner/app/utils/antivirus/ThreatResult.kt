package com.filecleaner.app.utils.antivirus

/**
 * Unified result from any scanner in the hybrid antivirus system.
 */
data class ThreatResult(
    val name: String,
    val description: String,
    val severity: Severity,
    val source: ScannerSource,
    val filePath: String? = null,
    val packageName: String? = null,
    val action: ThreatAction = ThreatAction.NONE
) {
    enum class Severity {
        INFO,
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    enum class ScannerSource {
        APP_INTEGRITY,
        FILE_SIGNATURE,
        PRIVACY_AUDIT
    }

    enum class ThreatAction {
        NONE,
        QUARANTINE,
        DELETE,
        UNINSTALL
    }
}
