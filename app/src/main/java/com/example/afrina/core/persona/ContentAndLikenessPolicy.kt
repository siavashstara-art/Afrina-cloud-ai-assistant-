package com.example.afrina.core.persona

/**
 * Safety, age, likeness, content, and tenant edition authorization rules
 * for persona customization and customer editions.
 */
object ContentAndLikenessPolicy {

    private val blockedContentMarkers = listOf(
        "csam",
        "child sexual",
        "minor nude",
        "underage sexual",
        "deepfake nonconsensual",
        "non-consensual",
        "bypass security policy",
        "disable licensing",
        "impersonate without consent",
    )

    fun validateTextContent(text: String, fieldName: String): Result<Unit> {
        val lower = text.lowercase()
        val matched = blockedContentMarkers.firstOrNull { lower.contains(it) }
        if (matched != null) {
            return Result.failure(
                SecurityException("Content policy violation in '$fieldName': prohibited safety pattern detected.")
            )
        }
        return Result.success(Unit)
    }

    fun validateLikenessOrVoiceAuthorization(
        explicitAuthorized: Boolean,
        subjectAgeStatus: SubjectAgePolicyStatus,
        assetKindLabel: String,
    ): Result<Unit> {
        if (!explicitAuthorized) {
            return Result.failure(
                SecurityException("Explicit owner/customer authorization is required before using $assetKindLabel.")
            )
        }
        return when (subjectAgeStatus) {
            SubjectAgePolicyStatus.VERIFIED_ADULT_18_PLUS -> Result.success(Unit)
            SubjectAgePolicyStatus.MINOR_RESTRICTED -> Result.failure(
                SecurityException("Age policy violation: likeness or voice registration for minors is strictly prohibited.")
            )
            SubjectAgePolicyStatus.UNVERIFIED -> Result.failure(
                SecurityException("Age policy gate: verified 18+ age status is required for likeness/voice customization.")
            )
        }
    }

    fun evaluateCustomerWorkspaceCapability(
        edition: AfrinaEdition,
        capability: CustomerWorkspaceCapability,
    ): DevelopmentPermissionDecision {
        return when (capability) {
            CustomerWorkspaceCapability.MODIFY_AFRINA_CORE,
            CustomerWorkspaceCapability.MODIFY_LICENSING,
            CustomerWorkspaceCapability.MODIFY_SECURITY_CONTROLS,
            CustomerWorkspaceCapability.MODIFY_INTERNAL_EXECUTION_POLICIES,
            CustomerWorkspaceCapability.ACCESS_MASTER_OR_OTHER_TENANT -> {
                DevelopmentPermissionDecision.Denied(
                    reasonEn = "Capability '${capability.name}' is strictly blocked; customer editions cannot modify AFRINA core, licensing, security policies, or cross-tenant data.",
                    reasonFa = "دسترسی «${capability.name}» مسدود است؛ نسخه مشتری نمی‌تواند هسته آفرینا، مجوزها، سیاست‌های امنیتی یا داده‌های سایر کاربران را تغییر دهد.",
                )
            }

            CustomerWorkspaceCapability.CUSTOMIZE_OWN_PERSONA_IDENTITY,
            CustomerWorkspaceCapability.CUSTOMIZE_OWN_AVATAR_AND_VOICE,
            CustomerWorkspaceCapability.CONFIGURE_OWN_DIGITAL_TWIN,
            CustomerWorkspaceCapability.DEVELOP_EXTERNAL_CUSTOMER_PROJECT -> {
                if (edition == AfrinaEdition.MASTER_OWNER || edition == AfrinaEdition.CUSTOMER_STANDARD) {
                    DevelopmentPermissionDecision.Allowed(
                        reasonEn = "Allowed inside isolated tenant workspace for ${edition.name}.",
                        reasonFa = "در فضای کاری ایزوله نسخه ${edition.name} مجاز است.",
                    )
                } else {
                    DevelopmentPermissionDecision.Denied("Unsupported edition", "نسخه نامعتبر")
                }
            }
        }
    }
}
