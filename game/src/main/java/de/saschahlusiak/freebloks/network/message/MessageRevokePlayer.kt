package de.saschahlusiak.freebloks.network.message

import de.saschahlusiak.freebloks.network.*
import java.nio.ByteBuffer

data class MessageRevokePlayer(val player: Int): Message(MessageType.RevokePlayer, 1) {
    init {
        assert(player in 0..3) { "Invalid player $player" }
    }

    override fun write(buffer: ByteBuffer) {
        super.write(buffer)
        buffer.put(player.toByte())
    }

    companion object {
        fun from(data: ByteBuffer): MessageRevokePlayer {
            val player = data.get().toInt()
            return MessageRevokePlayer(player)
        }
    }
}