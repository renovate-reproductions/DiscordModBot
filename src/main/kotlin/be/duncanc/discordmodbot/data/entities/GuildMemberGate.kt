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

package be.duncanc.discordmodbot.data.entities

import javax.persistence.Column
import javax.persistence.ElementCollection
import javax.persistence.Entity
import javax.persistence.Id


@Entity
data class GuildMemberGate(
    @Id
    val guildId: Long,
    val memberRole: Long? = null,
    val rulesTextChannel: Long? = null,
    val gateTextChannel: Long? = null,
    val welcomeTextChannel: Long? = null,
    val removeTimeHours: Long? = null,
    val reminderTimeHours: Long? = null,
    @ElementCollection
    @Column(name = "question")
    val questions: MutableSet<String> = HashSet()
)
