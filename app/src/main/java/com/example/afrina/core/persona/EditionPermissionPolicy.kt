package com.example.afrina.core.persona

/**
 * Enforces strict separation between Master Owner and Customer editions:
 * - Master edition may propose, generate, test, and improve code ONLY inside an isolated
 *   development environment AND through an owner-approved workflow.
 * - Customer editions NEVER receive permission to modify AFRINA core, licensing,
 *   security controls, internal execution policies, or workflow code.
 */
object EditionPermissionPolicy {
    fun evaluate(
        edition: AfrinaEdition,
        capability: DevelopmentCapability,
        inIsolatedDevEnvironment: Boolean,
        ownerApprovedWorkflow: Boolean,
    ): DevelopmentPermissionDecision {
        if (edition == AfrinaEdition.CUSTOMER_STANDARD) {
            return DevelopmentPermissionDecision.Denied(
                reasonEn = "Customer edition cannot modify AFRINA core, licensing, security controls, internal execution policies, or code.",
                reasonFa = "نسخه مشتری مجاز به تغییر هسته آفرینا، مجوزها، کنترل‌های امنیتی، سیاست‌های داخلی اجرا یا کد نیست.",
            )
        }

        return when (capability) {
            DevelopmentCapability.MODIFY_AFRINA_CORE,
            DevelopmentCapability.MODIFY_LICENSING,
            DevelopmentCapability.MODIFY_SECURITY_CONTROLS,
            DevelopmentCapability.MODIFY_INTERNAL_EXECUTION_POLICIES -> {
                DevelopmentPermissionDecision.Denied(
                    reasonEn = "Protected governance capability '${capability.name}' cannot be modified by automated workflows without dedicated core governance.",
                    reasonFa = "قابلیت حفاظتی '${capability.name}' بدون حاکمیت صریح هسته توسط چرخه خودکار قابل تغییر نیست.",
                )
            }

            DevelopmentCapability.PROPOSE_CODE,
            DevelopmentCapability.GENERATE_CODE_PATCH,
            DevelopmentCapability.RUN_ISOLATED_TESTS,
            DevelopmentCapability.IMPROVE_WORKFLOW_CODE -> {
                if (!inIsolatedDevEnvironment) {
                    DevelopmentPermissionDecision.Denied(
                        reasonEn = "Master development action '${capability.name}' requires an isolated development environment.",
                        reasonFa = "عملیات توسعه '${capability.name}' در نسخه مستر نیازمند محیط توسعه ایزوله است.",
                    )
                } else if (!ownerApprovedWorkflow) {
                    DevelopmentPermissionDecision.Denied(
                        reasonEn = "Master development action '${capability.name}' requires explicit owner-approved workflow sign-off.",
                        reasonFa = "عملیات توسعه '${capability.name}' نیازمند تایید صریح مالک سیستم در جریان کاری تاییدشده است.",
                    )
                } else {
                    DevelopmentPermissionDecision.Allowed(
                        reasonEn = "Allowed for Master edition inside isolated development environment with owner approval.",
                        reasonFa = "مجاز برای نسخه مستر در محیط توسعه ایزوله با تایید مالک.",
                    )
                }
            }
        }
    }
}
