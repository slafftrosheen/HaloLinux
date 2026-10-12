package com.halo.sdk.runtime.skills

@JvmInline
value class SkillId(val value: String)

enum class SkillCategory {
    WORKSPACE,
    DEVICE,
    COMMUNICATION,
    MEDIA,
    NAVIGATION,
    INFORMATION,
    AUTOMATION
}

enum class SkillRisk {
    READ_ONLY,
    REVERSIBLE,
    EXTERNAL_SIDE_EFFECT,
    SENSITIVE,
    DESTRUCTIVE
}

enum class HostPermission {
    MICROPHONE,
    CAMERA,
    LOCATION,
    NOTIFICATIONS,
    FILES,
    BLUETOOTH,
    ACCESSIBILITY,
    CONTACTS,
    CALENDAR_READ,
    CALENDAR_WRITE,
    NOTIFICATION_ACCESS,
    WRITE_SETTINGS,
    NOTIFICATION_POLICY,
}

sealed interface SkillRequirement {
    data class Permission(val permission: HostPermission) : SkillRequirement
    data class MinSdk(val api: Int) : SkillRequirement
    data class ToolRegistered(val toolId: String) : SkillRequirement
    data class DeviceConnected(val deviceKind: String) : SkillRequirement
    data class ProviderCapability(val capability: String) : SkillRequirement
    data class RuntimeFeature(val feature: String) : SkillRequirement
}

sealed interface SkillAvailability {
    data object Available : SkillAvailability
    data class PartiallyAvailable(val unavailableTools: Set<String>) : SkillAvailability
    data class MissingPermission(val permission: HostPermission) : SkillAvailability
    data class MissingDependency(val dependency: String) : SkillAvailability
    data object UnsupportedDevice : SkillAvailability
    data object Disabled : SkillAvailability
}

enum class SkillSource {
    BUILT_IN,
    CUSTOM,
    APP_FUNCTIONS
}

data class SkillProvenance(
    val sourceId: String,
    val contentSha256: String,
    val loadedAtMs: Long,
)

data class SkillToolDefinition(
    val toolId: String,
    val risk: SkillRisk = SkillRisk.READ_ONLY,
    val requiredPermissions: Set<HostPermission> = emptySet(),
    val defaultApproval: String = "AUTOMATIC_SAFE"
)

data class SkillDescriptor(
    val id: SkillId,
    val name: String,
    val description: String,
    val category: SkillCategory,
    val schemaVersion: Int = 2,
    val enabled: Boolean = true,
    val tools: Set<String> = emptySet(),
    val toolDefinitions: List<SkillToolDefinition> = emptyList(),
    val requirements: List<SkillRequirement> = emptyList(),
    val requiredPermissions: Set<HostPermission> = emptySet(),
    val risk: SkillRisk = SkillRisk.READ_ONLY,
    val availability: SkillAvailability = SkillAvailability.Available,
    val source: SkillSource = SkillSource.BUILT_IN,
    val provenance: SkillProvenance? = null,
)

object SkillManifestPolicy {
    const val CURRENT_SCHEMA_VERSION = 2
    const val MAX_MANIFEST_BYTES = 64 * 1024
    private val idPattern = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+")
    private val toolPattern = Regex("[a-z][a-z0-9_]{2,79}")

    fun validate(descriptor: SkillDescriptor): List<String> = buildList {
        if (descriptor.schemaVersion != CURRENT_SCHEMA_VERSION) add("Unsupported schemaVersion ${descriptor.schemaVersion}")
        if (!idPattern.matches(descriptor.id.value)) add("Invalid skill id")
        if (descriptor.name.isBlank() || descriptor.name.length > 80) add("Skill name must contain 1-80 characters")
        if (descriptor.description.isBlank() || descriptor.description.length > 500) add("Skill description must contain 1-500 characters")
        if (descriptor.tools.isEmpty()) add("Skill must declare at least one tool")
        descriptor.tools.filterNot(toolPattern::matches).forEach { add("Invalid tool id: $it") }
        if (descriptor.toolDefinitions.map { it.toolId }.toSet().size != descriptor.toolDefinitions.size) add("Duplicate tool definitions")
        descriptor.toolDefinitions.filter { it.toolId !in descriptor.tools }.forEach { add("Tool definition not declared in tools: ${it.toolId}") }
    }
}
