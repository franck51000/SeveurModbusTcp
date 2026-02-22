package com.example.modbustcp.modbus

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Modbus TCP/IP client implementation.
 * Implements all standard Modbus function codes over TCP/IP (MBAP).
 */
class ModbusClient(
    private val host: String,
    private val port: Int = 502,
    private val unitId: Int = 1,
    private val timeoutMs: Int = 3000
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val transactionCounter = AtomicInteger(0)

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /** Connect to the Modbus server. */
    fun connect() {
        val s = Socket()
        s.soTimeout = timeoutMs
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        outputStream = s.getOutputStream()
        inputStream = s.getInputStream()
    }

    /** Disconnect from the Modbus server. */
    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outputStream = null
        inputStream = null
    }

    // ─── FC01: Read Coils ─────────────────────────────────────────────────────

    /** FC01 – Read Coils. Returns list of Boolean values. */
    fun readCoils(startAddress: Int, quantity: Int): List<Boolean> {
        val response = sendRequest(ModbusFunctionCode.READ_COILS.code, startAddress, quantity)
        val byteCount = response.data[0].toInt() and 0xFF
        val coilData = response.data.copyOfRange(1, 1 + byteCount)
        return ModbusResponse(response.transactionId, response.functionCode, coilData)
            .getCoils(quantity)
    }

    // ─── FC02: Read Discrete Inputs ───────────────────────────────────────────

    /** FC02 – Read Discrete Inputs. Returns list of Boolean values. */
    fun readDiscreteInputs(startAddress: Int, quantity: Int): List<Boolean> {
        val response = sendRequest(ModbusFunctionCode.READ_DISCRETE_INPUTS.code, startAddress, quantity)
        val byteCount = response.data[0].toInt() and 0xFF
        val coilData = response.data.copyOfRange(1, 1 + byteCount)
        return ModbusResponse(response.transactionId, response.functionCode, coilData)
            .getCoils(quantity)
    }

    // ─── FC03: Read Holding Registers ─────────────────────────────────────────

    /** FC03 – Read Holding Registers. Returns list of register values (0–65535). */
    fun readHoldingRegisters(startAddress: Int, quantity: Int): List<Int> {
        val response = sendRequest(ModbusFunctionCode.READ_HOLDING_REGISTERS.code, startAddress, quantity)
        val byteCount = response.data[0].toInt() and 0xFF
        val regData = response.data.copyOfRange(1, 1 + byteCount)
        return ModbusResponse(response.transactionId, response.functionCode, regData).getRegisters()
    }

    // ─── FC04: Read Input Registers ───────────────────────────────────────────

    /** FC04 – Read Input Registers. Returns list of register values (0–65535). */
    fun readInputRegisters(startAddress: Int, quantity: Int): List<Int> {
        val response = sendRequest(ModbusFunctionCode.READ_INPUT_REGISTERS.code, startAddress, quantity)
        val byteCount = response.data[0].toInt() and 0xFF
        val regData = response.data.copyOfRange(1, 1 + byteCount)
        return ModbusResponse(response.transactionId, response.functionCode, regData).getRegisters()
    }

    // ─── FC05: Write Single Coil ──────────────────────────────────────────────

    /** FC05 – Write a single coil. */
    fun writeSingleCoil(address: Int, value: Boolean) {
        val coilValue = if (value) 0xFF00 else 0x0000
        sendRequest(ModbusFunctionCode.WRITE_SINGLE_COIL.code, address, coilValue)
    }

    // ─── FC06: Write Single Register ──────────────────────────────────────────

    /** FC06 – Write a single holding register. */
    fun writeSingleRegister(address: Int, value: Int) {
        sendRequest(ModbusFunctionCode.WRITE_SINGLE_REGISTER.code, address, value)
    }

    // ─── FC15: Write Multiple Coils ───────────────────────────────────────────

    /** FC15 – Write multiple coils. */
    fun writeMultipleCoils(startAddress: Int, values: List<Boolean>) {
        val byteCount = (values.size + 7) / 8
        val coilBytes = ByteArray(byteCount)
        for (i in values.indices) {
            if (values[i]) {
                coilBytes[i / 8] = (coilBytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        val pdu = buildPdu(ModbusFunctionCode.WRITE_MULTIPLE_COILS.code) {
            addShort(startAddress)
            addShort(values.size)
            addByte(byteCount)
            addBytes(coilBytes)
        }
        sendPdu(pdu)
    }

    // ─── FC16: Write Multiple Registers ──────────────────────────────────────

    /** FC16 – Write multiple holding registers. */
    fun writeMultipleRegisters(startAddress: Int, values: List<Int>) {
        val byteCount = values.size * 2
        val pdu = buildPdu(ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS.code) {
            addShort(startAddress)
            addShort(values.size)
            addByte(byteCount)
            for (v in values) addShort(v)
        }
        sendPdu(pdu)
    }

    // ─── FC22: Mask Write Register ────────────────────────────────────────────

    /** FC22 – Mask Write Register. result = (current AND andMask) OR (orMask AND NOT andMask). */
    fun maskWriteRegister(address: Int, andMask: Int, orMask: Int) {
        val pdu = buildPdu(ModbusFunctionCode.MASK_WRITE_REGISTER.code) {
            addShort(address)
            addShort(andMask)
            addShort(orMask)
        }
        sendPdu(pdu)
    }

    // ─── FC23: Read/Write Multiple Registers ──────────────────────────────────

    /** FC23 – Read/Write Multiple Registers in one operation. */
    fun readWriteMultipleRegisters(
        readAddress: Int, readQuantity: Int,
        writeAddress: Int, writeValues: List<Int>
    ): List<Int> {
        val byteCount = writeValues.size * 2
        val pdu = buildPdu(ModbusFunctionCode.READ_WRITE_MULTIPLE_REGISTERS.code) {
            addShort(readAddress)
            addShort(readQuantity)
            addShort(writeAddress)
            addShort(writeValues.size)
            addByte(byteCount)
            for (v in writeValues) addShort(v)
        }
        val response = sendPdu(pdu)
        val respByteCount = response.data[0].toInt() and 0xFF
        val regData = response.data.copyOfRange(1, 1 + respByteCount)
        return ModbusResponse(response.transactionId, response.functionCode, regData).getRegisters()
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private fun sendRequest(functionCode: Int, startAddress: Int, value: Int): ModbusResponse {
        val pdu = buildPdu(functionCode) {
            addShort(startAddress)
            addShort(value)
        }
        return sendPdu(pdu)
    }

    private fun sendPdu(pdu: ByteArray): ModbusResponse {
        val os = outputStream ?: throw ModbusException("Not connected")
        val iStream = inputStream ?: throw ModbusException("Not connected")

        os.write(pdu)
        os.flush()

        // Read MBAP header (6 bytes)
        val header = ByteArray(6)
        readFully(iStream, header)

        val transactionId = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val length = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)

        // Read PDU
        val pduResponse = ByteArray(length)
        readFully(iStream, pduResponse)

        val fc = pduResponse[0].toInt() and 0xFF

        // Check for exception response
        if (fc and 0x80 != 0) {
            val originalFc = fc and 0x7F
            val exCode = pduResponse[1].toInt() and 0xFF
            throw ModbusException.fromExceptionCode(originalFc, exCode)
        }

        val data = pduResponse.copyOfRange(1, pduResponse.size)
        return ModbusResponse(transactionId, fc, data)
    }

    private fun readFully(stream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw ModbusException("Connection closed by remote host")
            offset += read
        }
    }

    private fun buildPdu(functionCode: Int, block: PduBuilder.() -> Unit): ByteArray {
        val builder = PduBuilder(unitId, transactionCounter.incrementAndGet() and 0xFFFF, functionCode)
        builder.block()
        return builder.build()
    }

    // ─── PDU builder ──────────────────────────────────────────────────────────

    private class PduBuilder(
        private val unitId: Int,
        private val transactionId: Int,
        private val functionCode: Int
    ) {
        private val payload = mutableListOf<Byte>()

        fun addShort(value: Int) {
            payload.add(((value shr 8) and 0xFF).toByte())
            payload.add((value and 0xFF).toByte())
        }

        fun addByte(value: Int) {
            payload.add((value and 0xFF).toByte())
        }

        fun addBytes(bytes: ByteArray) {
            bytes.forEach { payload.add(it) }
        }

        fun build(): ByteArray {
            // MBAP: transaction(2) + protocol(2=0x0000) + length(2) + unitId(1) + fc(1) + payload
            val pduLength = 1 + 1 + payload.size  // unitId + fc + payload
            val frame = ByteArray(6 + 1 + 1 + payload.size)
            frame[0] = ((transactionId shr 8) and 0xFF).toByte()
            frame[1] = (transactionId and 0xFF).toByte()
            frame[2] = 0x00  // protocol high
            frame[3] = 0x00  // protocol low
            frame[4] = ((pduLength shr 8) and 0xFF).toByte()
            frame[5] = (pduLength and 0xFF).toByte()
            frame[6] = (unitId and 0xFF).toByte()
            frame[7] = (functionCode and 0xFF).toByte()
            for (i in payload.indices) frame[8 + i] = payload[i]
            return frame
        }
    }
}
