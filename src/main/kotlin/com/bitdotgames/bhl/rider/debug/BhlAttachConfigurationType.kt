package com.bitdotgames.bhl.rider.debug

import com.bitdotgames.bhl.rider.BhlIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

class BhlAttachConfigurationType : ConfigurationTypeBase(
    "BhlAttachConfiguration",
    "BHL: Attach to Debug Server",
    "Attach the BHL debugger to a running bhl debug server over TCP",
    BhlIcons.FILE,
) {
    init {
        addFactory(BhlAttachConfigurationFactory(this))
    }
}

class BhlAttachConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "BhlAttachConfigurationFactory"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        BhlAttachRunConfiguration(project, this, "BHL Attach")
}
