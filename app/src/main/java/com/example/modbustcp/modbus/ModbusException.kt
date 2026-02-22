package com.example.modbustcp.modbus

/**
 * Exception thrown when a Modbus error occurs.
 */
class ModbusException(message: String, val errorCode: Int = -1) : Exception(message) {
    companion object {
        fun fromExceptionCode(functionCode: Int, exceptionCode: Int): ModbusException {
            val description = when (exceptionCode) {
                0x01 -> "Illegal Function"
                0x02 -> "Illegal Data Address"
                0x03 -> "Illegal Data Value"
                0x04 -> "Server Device Failure"
                0x05 -> "Acknowledge"
                0x06 -> "Server Device Busy"
                0x08 -> "Memory Parity Error"
                0x0A -> "Gateway Path Unavailable"
                0x0B -> "Gateway Target Device Failed to Respond"
                else -> "Unknown Exception Code: $exceptionCode"
            }
            return ModbusException(
                "Modbus Exception (FC=0x${functionCode.toString(16).uppercase()}): $description",
                exceptionCode
            )
        }
    }
}
