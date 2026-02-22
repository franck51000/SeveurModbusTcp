package com.example.modbustcp.modbus

/**
 * Modbus function codes as defined in the Modbus specification.
 */
enum class ModbusFunctionCode(val code: Int) {
    READ_COILS(0x01),
    READ_DISCRETE_INPUTS(0x02),
    READ_HOLDING_REGISTERS(0x03),
    READ_INPUT_REGISTERS(0x04),
    WRITE_SINGLE_COIL(0x05),
    WRITE_SINGLE_REGISTER(0x06),
    WRITE_MULTIPLE_COILS(0x0F),
    WRITE_MULTIPLE_REGISTERS(0x10),
    READ_WRITE_MULTIPLE_REGISTERS(0x17),
    MASK_WRITE_REGISTER(0x16),
    READ_FIFO_QUEUE(0x18);

    companion object {
        fun fromCode(code: Int): ModbusFunctionCode? = values().find { it.code == code }
    }
}
