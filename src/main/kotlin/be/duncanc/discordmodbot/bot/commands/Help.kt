/*
 * Copyright 2018 Duncan Casteleyn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package be.duncanc.discordmodbot.bot.commands

import be.duncanc.discordmodbot.data.services.UserBlockService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Will create a help command containing information about commands.
 *
 * @since 1.0.0
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
class Help(
    userBlockService: UserBlockService
) : CommandModule(
    arrayOf("Help"),
    null,
    "Show a list of commands",
    userBlockService = userBlockService
) {

    /**
     * Sends an embed to the users containing help for the commands
     */
    override fun commandExec(event: MessageReceivedEvent, command: String, arguments: String?) {
        if (
            !event.isFromType(ChannelType.PRIVATE) &&
            (event.isFromType(ChannelType.TEXT) && event.member?.hasPermission(Permission.MESSAGE_MANAGE) != true)
        ) {
            throw UnsupportedOperationException("The help command should be executed in private chat")
        }
        val helpEmbeds: MutableList<EmbedBuilder> = mutableListOf(EmbedBuilder().setTitle("Help"))
        event.jda.registeredListeners.filterIsInstance<CommandModule>().forEach {
            if (helpEmbeds[helpEmbeds.lastIndex].fields.count() >= 25) {
                helpEmbeds.add(EmbedBuilder().setTitle("Help part ${helpEmbeds.size + 1}"))
            }
            helpEmbeds[helpEmbeds.lastIndex].addField(
                "${
                    it.aliases.contentToString().replace("[", "").replace(
                        "]",
                        ""
                    )
                }${if (it.argumentationSyntax != null) " ${it.argumentationSyntax}" else ""}",
                (it.description
                    ?: "No description available.") +
                        (if (it.requiredPermissions.isNotEmpty()) "Requires server permissions: ${it.requiredPermissions.contentToString()}" else ""),
                false
            )
        }

        helpEmbeds.forEach { embedBuilder ->
            event.channel.sendMessageEmbeds(embedBuilder.build()).queue()
        }
    }
}
