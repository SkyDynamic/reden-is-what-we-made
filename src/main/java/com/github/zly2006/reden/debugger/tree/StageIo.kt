package com.github.zly2006.reden.debugger.tree

import com.github.zly2006.reden.Reden
import com.github.zly2006.reden.debugger.TickStage
import com.github.zly2006.reden.debugger.TickStageWithWorld
import com.github.zly2006.reden.debugger.stages.DummyStage
import com.github.zly2006.reden.debugger.stages.ServerRootStage
import com.github.zly2006.reden.debugger.stages.WorldRootStage
import com.github.zly2006.reden.debugger.stages.block.*
import com.github.zly2006.reden.debugger.stages.world.*
import com.github.zly2006.reden.debugger.tree.StageIo.Constructor
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.network.PacketByteBuf
import net.minecraft.server.world.ServerWorld

object StageIo {
    fun interface Constructor {
        fun construct(parent: TickStage?): TickStage
    }
    val constructors = mutableMapOf<String, Constructor>()

    init {
        class EmptyTickStage(name: String, parent: TickStage?): TickStage(name, parent), TickStageWithWorld {
            override val world: ServerWorld?
                get() {
                    Reden.LOGGER.warn("Accessing world from client side at $name")
                    return null
                }
        }

        // Note: these stages have no extra data, so we can use empty constructor to simplify code
        constructors["server_root"] = Constructor { ServerRootStage(null) }
        constructors["end"] = Constructor { EmptyTickStage("end", it) }
        constructors["global_network"] = Constructor { EmptyTickStage("global_network", it) }

        constructors["world_root"] = Constructor { WorldRootStage(null, it!!, null) }
        constructors["network"] = Constructor { EmptyTickStage("network", it) }
        constructors["update_block"] = Constructor { EmptyTickStage("update_block", it!!) }
        constructors["commands_stage"] = Constructor { EmptyTickStage("commands_stage", it!!) }

        constructors["block_update"] = Constructor { BlockUpdateStage(it!!) }
        constructors["nc_update"] = Constructor { StageBlockNCUpdate(it!!, null) }
        constructors["nc_update_1"] = Constructor { StageBlockNCUpdateSixWay.StageBlockNCUpdateOneWay(it!!, direction = null) }
        constructors["nc_update_6"] = Constructor { StageBlockNCUpdateSixWay(it!!, null) }
        constructors["nc_update_with_source"] = Constructor { StageBlockNCUpdateWithSource(it!!, null) }
        constructors["pp_update"] = Constructor { StageBlockPPUpdate(it!!, null) }

        constructors["entities"] = Constructor { EntitiesRootStage(null) }
        constructors["entity"] = Constructor { EntityStage(it as EntitiesRootStage, null) }
        constructors["block_entities_root"] = Constructor { BlockEntitiesRootStage(null) }
        constructors["block_entity"] = Constructor { BlockEntityStage(it as BlockEntitiesRootStage, null) }
        constructors["block_events_root"] = Constructor { BlockEventsRootStage(null) }
        constructors["block_event"] = Constructor { BlockEventStage(it as BlockEventsRootStage, null) }
        constructors["block_scheduled_ticks_root"] = Constructor { BlockScheduledTicksRootStage(null) }
        constructors["block_scheduled_tick"] = Constructor { BlockScheduledTickStage(it as BlockScheduledTicksRootStage, null) }
        constructors["fluid_scheduled_ticks_root"] = Constructor { FluidScheduledTicksRootStage(null) }
        constructors["fluid_scheduled_tick"] = Constructor { FluidScheduledTickStage(it as FluidScheduledTicksRootStage, null) }

        constructors["raid"] = Constructor { EmptyTickStage("raid", it!!) }
        constructors["random_tick"] = Constructor { EmptyTickStage("random_tick", it!!) }
        constructors["spawn"] = Constructor { EmptyTickStage("spawn", it!!) }
        constructors["special_spawn"] = Constructor { EmptyTickStage("special_spawn", it!!) }
        constructors["time"] = Constructor { EmptyTickStage("time", it!!) }
        constructors["weather"] = Constructor { EmptyTickStage("weather", it!!) }
        constructors["world_border"] = Constructor { EmptyTickStage("world_border", it!!) }
    }

    @Deprecated("", replaceWith = ReplaceWith("writeSingleTickStage", imports = ["com.github.zly2006.reden.debugger.tree.StageIo"]))
    fun writeStage(stage: TickStage, buf: PacketByteBuf) {
        buf.writeBoolean(stage.parent != null)
        buf.writeVarInt(stage.children.size)
        buf.writeString(stage.name)

        stage.writeByteBuf(buf)
    }

    fun writeTickStageTree(packetByteBuf: PacketByteBuf, tickStageTree: TickStageTree) {
        writeTickStageTree(packetByteBuf, tickStageTree, false)
    }

    fun writeTickStageTree(buf: PacketByteBuf, tickStageTree: TickStageTree, writeAllChildren: Boolean) {
        buf.writeBoolean(writeAllChildren)
        if (writeAllChildren) {
            var lastStage = tickStageTree.activeStages.first()
            val indexes = IntArrayList(tickStageTree.activeStages.size - 1)
            tickStageTree.activeStages.asSequence().drop(1).forEach { stage ->
                indexes.add(lastStage.children.indexOf(stage))
                lastStage = stage
            }
            fun writeStage(stage: TickStage) {
                writeSingleTickStage(buf, stage)
                stage.children.forEach(::writeStage)

                buf.writeVarInt(233) // 233
            }

            buf.writeIntList(indexes)
            writeStage(tickStageTree.activeStages.first())
        } else {
            val list = tickStageTree.activeStages
            buf.writeCollection(list, ::writeSingleTickStage)
        }
    }

    fun writeSingleTickStage(buf: PacketByteBuf, tickStage: TickStage) {
        buf.writeNullable(tickStage.parent?.id, PacketByteBuf::writeVarInt)
        buf.writeVarInt(tickStage.id)
        buf.writeVarInt(tickStage.children.size)
        buf.writeString(tickStage.name)
        tickStage.writeByteBuf(buf)
    }

    fun readTickStageTree(buf: PacketByteBuf): TickStageTree {
        val writeAllChildren = buf.readBoolean()
        var lastRead: TickStage? = null
        fun readSingleTickStage(buf: PacketByteBuf): TickStage {
            val parentId = buf.readNullable(PacketByteBuf::readVarInt)
            val id = buf.readVarInt()
            val childrenSize = buf.readVarInt()
            val name = buf.readString()

            val stage = constructors[name]?.construct(lastRead)
                ?: error("Unknown stage name: $name")
            stage.readByteBuf(buf)

            for (i in 0 until childrenSize) {
                stage.children.add(DummyStage(stage))
            }

            return stage
        }

        val list: ArrayList<TickStage>
        if (writeAllChildren) {
            fun readStage(): TickStage {
                val thisStage = readSingleTickStage(buf)
                thisStage.children.clear()
                repeat(thisStage.children.size) {
                    lastRead = thisStage // reset the last read stage in our loop.
                    thisStage.children.add(readStage())
                }
                assert(buf.readVarInt() == 233) // 233
                return thisStage
            }

            val indexes = buf.readIntList()
            val root = readStage()
            list = ArrayList(indexes.size + 1)
            list.add(root)
            indexes.fold(root) { prev, index ->
                prev.children[index]
                    .also { list.add(it) }
            }
        } else {
            list = buf.readCollection(::ArrayList) {
                val stage = readSingleTickStage(buf)
                lastRead = stage
                stage
            }
        }
        return TickStageTree(activeStages = list)
    }
}
