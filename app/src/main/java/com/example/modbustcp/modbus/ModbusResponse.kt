package com.example.modbustcp.modbus

/**
 * Represents a response from a Modbus server.
 */
data class ModbusResponse(
    val transactionId: Int,
    val functionCode: Int,
    val data: ByteArray
) {
    /** Returns registers as list of Int (16-bit unsigned values). */
    fun getRegisters(): List<Int> {
        val registers = mutableListOf<Int>()
        var i = 0
        while (i + 1 < data.size) {
            val high = data[i].toInt() and 0xFF
            val low = data[i + 1].toInt() and 0xFF
            registers.add((high shl 8) or low)
            i += 2
        }
        return registers
    }

    /** Returns coils as list of Boolean values. */
    fun getCoils(count: Int): List<Boolean> {
        val coils = mutableListOf<Boolean>()
        for (i in 0 until count) {
            val byteIndex = i / 8
            val bitIndex = i % 8
            if (byteIndex < data.size) {
                coils.add((data[byteIndex].toInt() shr bitIndex) and 0x01 == 1)
            }
        }
        return coils
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ModbusResponse
        return transactionId == other.transactionId &&
                functionCode == other.functionCode &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = transactionId
        result = 31 * result + functionCode
        result = 31 * result + data.contentHashCode()
        return result
    }
}
