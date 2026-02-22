package com.example.modbustcp.ui

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.modbustcp.R
import com.example.modbustcp.databinding.ActivityMainBinding
import com.example.modbustcp.modbus.ModbusClient
import com.example.modbustcp.modbus.ModbusFunctionCode
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display format for register values. */
enum class DisplayFormat { DECIMAL, HEX, BINARY }

/** Wrapper that pairs a Modbus function code with a user-readable label. */
data class FunctionCodeItem(val label: String, val code: ModbusFunctionCode) {
    override fun toString() = label
}

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var modbusClient: ModbusClient? = null
    private var displayFormat = DisplayFormat.DECIMAL
    private var cyclicJob: Job? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val functionCodeItems = listOf(
        FunctionCodeItem("FC01 – Read Coils",                      ModbusFunctionCode.READ_COILS),
        FunctionCodeItem("FC02 – Read Discrete Inputs",            ModbusFunctionCode.READ_DISCRETE_INPUTS),
        FunctionCodeItem("FC03 – Read Holding Registers",          ModbusFunctionCode.READ_HOLDING_REGISTERS),
        FunctionCodeItem("FC04 – Read Input Registers",            ModbusFunctionCode.READ_INPUT_REGISTERS),
        FunctionCodeItem("FC05 – Write Single Coil",               ModbusFunctionCode.WRITE_SINGLE_COIL),
        FunctionCodeItem("FC06 – Write Single Register",           ModbusFunctionCode.WRITE_SINGLE_REGISTER),
        FunctionCodeItem("FC15 – Write Multiple Coils",            ModbusFunctionCode.WRITE_MULTIPLE_COILS),
        FunctionCodeItem("FC16 – Write Multiple Registers",        ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS),
        FunctionCodeItem("FC22 – Mask Write Register",             ModbusFunctionCode.MASK_WRITE_REGISTER),
        FunctionCodeItem("FC23 – Read/Write Multiple Registers",   ModbusFunctionCode.READ_WRITE_MULTIPLE_REGISTERS),
    )

    private var selectedFunctionCode: ModbusFunctionCode = ModbusFunctionCode.READ_HOLDING_REGISTERS

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFunctionCodeSpinner()
        setupDisplayFormatToggle()
        setupButtons()
        setupCyclicSwitch()
        updateConnectionUi(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        cyclicJob?.cancel()
        lifecycleScope.launch(Dispatchers.IO) { modbusClient?.disconnect() }
    }

    // ─── Setup ─────────────────────────────────────────────────────────────────

    private fun setupFunctionCodeSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            functionCodeItems
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerFunctionCode.adapter = adapter
        binding.spinnerFunctionCode.setSelection(2) // Default: FC03
        binding.spinnerFunctionCode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                selectedFunctionCode = functionCodeItems[pos].code
                updateWriteFieldVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun updateWriteFieldVisibility() {
        val isWrite = selectedFunctionCode in listOf(
            ModbusFunctionCode.WRITE_SINGLE_COIL,
            ModbusFunctionCode.WRITE_SINGLE_REGISTER,
            ModbusFunctionCode.WRITE_MULTIPLE_COILS,
            ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS,
            ModbusFunctionCode.MASK_WRITE_REGISTER,
            ModbusFunctionCode.READ_WRITE_MULTIPLE_REGISTERS,
        )
        binding.tilWriteValue.visibility = if (isWrite) View.VISIBLE else View.GONE
        binding.btnWrite.isEnabled = isWrite
    }

    private fun setupDisplayFormatToggle() {
        binding.toggleDisplayFormat.check(R.id.btnFormatDec)
        binding.toggleDisplayFormat.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            displayFormat = when (checkedId) {
                R.id.btnFormatDec -> DisplayFormat.DECIMAL
                R.id.btnFormatHex -> DisplayFormat.HEX
                R.id.btnFormatBin -> DisplayFormat.BINARY
                else -> DisplayFormat.DECIMAL
            }
            // Refresh table with new format
            refreshTableDisplay()
        }
    }

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnRead.setOnClickListener { onReadClicked() }
        binding.btnWrite.setOnClickListener { onWriteClicked() }
        binding.btnClearLog.setOnClickListener { binding.tvLog.text = "" }
    }

    private fun setupCyclicSwitch() {
        binding.switchCyclic.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startCyclicRead() else stopCyclicRead()
        }
    }

    // ─── Connection ────────────────────────────────────────────────────────────

    private fun onConnectClicked() {
        if (modbusClient?.isConnected == true) {
            disconnectFromServer()
        } else {
            connectToServer()
        }
    }

    private fun connectToServer() {
        val host = binding.etIpAddress.text.toString().trim()
        val port = binding.etPort.text.toString().toIntOrNull() ?: 502
        val unitId = binding.etUnitId.text.toString().toIntOrNull() ?: 1

        if (host.isEmpty()) {
            appendLog("Erreur : adresse IP vide")
            return
        }

        setConnectingUi()
        appendLog("Connexion à $host:$port (unitId=$unitId)…")

        lifecycleScope.launch {
            try {
                val client = ModbusClient(host, port, unitId)
                withContext(Dispatchers.IO) { client.connect() }
                modbusClient = client
                updateConnectionUi(true)
                appendLog("✓ Connecté à $host:$port")
            } catch (e: Exception) {
                updateConnectionUi(false)
                appendLog("✗ Erreur de connexion : ${e.message}")
            }
        }
    }

    private fun disconnectFromServer() {
        cyclicJob?.cancel()
        binding.switchCyclic.isChecked = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { modbusClient?.disconnect() }
            modbusClient = null
            updateConnectionUi(false)
            appendLog("Déconnecté")
        }
    }

    // ─── Read ──────────────────────────────────────────────────────────────────

    private fun onReadClicked() {
        val client = modbusClient
        if (client == null || !client.isConnected) {
            appendLog("✗ Non connecté")
            return
        }
        performRead(client)
    }

    private fun performRead(client: ModbusClient) {
        val startAddr = binding.etStartAddress.text.toString().toIntOrNull() ?: 0
        val qty = binding.etQuantity.text.toString().toIntOrNull() ?: 1

        lifecycleScope.launch {
            try {
                val values: List<Int> = withContext(Dispatchers.IO) {
                    when (selectedFunctionCode) {
                        ModbusFunctionCode.READ_COILS ->
                            client.readCoils(startAddr, qty).map { if (it) 1 else 0 }
                        ModbusFunctionCode.READ_DISCRETE_INPUTS ->
                            client.readDiscreteInputs(startAddr, qty).map { if (it) 1 else 0 }
                        ModbusFunctionCode.READ_HOLDING_REGISTERS ->
                            client.readHoldingRegisters(startAddr, qty)
                        ModbusFunctionCode.READ_INPUT_REGISTERS ->
                            client.readInputRegisters(startAddr, qty)
                        ModbusFunctionCode.READ_WRITE_MULTIPLE_REGISTERS -> {
                            val writeAddr = startAddr
                            val writeVal = binding.etWriteValue.text.toString().toIntOrNull() ?: 0
                            client.readWriteMultipleRegisters(startAddr, qty, writeAddr, listOf(writeVal))
                        }
                        else -> emptyList()
                    }
                }
                displayRegisters(startAddr, values)
                appendLog("Lecture FC${fcHex(selectedFunctionCode)} @$startAddr ×$qty → OK")
            } catch (e: Exception) {
                appendLog("✗ Lecture : ${e.message}")
            }
        }
    }

    // ─── Write ─────────────────────────────────────────────────────────────────

    private fun onWriteClicked() {
        val client = modbusClient
        if (client == null || !client.isConnected) {
            appendLog("✗ Non connecté")
            return
        }

        val startAddr = binding.etStartAddress.text.toString().toIntOrNull() ?: 0
        val qty = binding.etQuantity.text.toString().toIntOrNull() ?: 1
        val value = binding.etWriteValue.text.toString().toIntOrNull() ?: 0

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (selectedFunctionCode) {
                        ModbusFunctionCode.WRITE_SINGLE_COIL ->
                            client.writeSingleCoil(startAddr, value != 0)
                        ModbusFunctionCode.WRITE_SINGLE_REGISTER ->
                            client.writeSingleRegister(startAddr, value)
                        ModbusFunctionCode.WRITE_MULTIPLE_COILS -> {
                            val coils = List(qty) { i -> (value shr i) and 1 == 1 }
                            client.writeMultipleCoils(startAddr, coils)
                        }
                        ModbusFunctionCode.WRITE_MULTIPLE_REGISTERS -> {
                            val regs = List(qty) { value }
                            client.writeMultipleRegisters(startAddr, regs)
                        }
                        ModbusFunctionCode.MASK_WRITE_REGISTER -> {
                            val andMask = value and 0xFFFF
                            val orMask = 0x0000
                            client.maskWriteRegister(startAddr, andMask, orMask)
                        }
                        else -> {}
                    }
                }
                appendLog("Écriture FC${fcHex(selectedFunctionCode)} @$startAddr ×$qty val=$value → OK")
            } catch (e: Exception) {
                appendLog("✗ Écriture : ${e.message}")
            }
        }
    }

    // ─── Cyclic mode ───────────────────────────────────────────────────────────

    private fun startCyclicRead() {
        val client = modbusClient
        if (client == null || !client.isConnected) {
            appendLog("✗ Non connecté – mode cyclique impossible")
            binding.switchCyclic.isChecked = false
            return
        }

        val intervalMs = binding.etCycleInterval.text.toString().toLongOrNull() ?: 1000L
        appendLog("Mode cyclique démarré (intervalle=${intervalMs}ms)")

        cyclicJob = lifecycleScope.launch {
            while (isActive) {
                if (client.isConnected) {
                    performRead(client)
                } else {
                    appendLog("✗ Connexion perdue – mode cyclique arrêté")
                    binding.switchCyclic.isChecked = false
                    break
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopCyclicRead() {
        cyclicJob?.cancel()
        cyclicJob = null
        appendLog("Mode cyclique arrêté")
    }

    private fun fcHex(fc: ModbusFunctionCode) = fc.code.toString(16).uppercase().padStart(2, '0')

    private var lastStartAddress = 0
    private var lastValues = listOf<Int>()

    private fun displayRegisters(startAddress: Int, values: List<Int>) {
        lastStartAddress = startAddress
        lastValues = values
        refreshTableDisplay()
    }

    private fun refreshTableDisplay() {
        val container = binding.tableContainer
        container.removeAllViews()

        for ((index, value) in lastValues.withIndex()) {
            val address = lastStartAddress + index
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(
                    if (index % 2 == 0)
                        ContextCompat.getColor(this@MainActivity, R.color.row_even)
                    else
                        ContextCompat.getColor(this@MainActivity, R.color.row_odd)
                )
                setPadding(4, 6, 4, 6)
            }

            row.addView(makeCell("$address", 1))
            row.addView(makeCell(formatValue(value, DisplayFormat.DECIMAL), 2))
            row.addView(makeCell(formatValue(value, DisplayFormat.HEX), 2))
            row.addView(makeCell(formatValue(value, DisplayFormat.BINARY), 3))

            container.addView(row)
        }
    }

    private fun makeCell(text: String, weight: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight.toFloat())
            gravity = android.view.Gravity.CENTER
            setPadding(4, 0, 4, 0)
        }
    }

    private fun formatValue(value: Int, format: DisplayFormat): String {
        return when (format) {
            DisplayFormat.DECIMAL -> value.toString()
            DisplayFormat.HEX -> "0x${value.toString(16).uppercase().padStart(4, '0')}"
            DisplayFormat.BINARY -> value.toString(2).padStart(16, '0').chunked(4).joinToString(" ")
        }
    }

    // ─── UI state helpers ──────────────────────────────────────────────────────

    private fun updateConnectionUi(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                binding.btnConnect.text = getString(R.string.btn_disconnect)
                binding.btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.connected)
                binding.tvConnectionStatus.text = getString(R.string.status_connected)
                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.connected))
                setStatusDotColor(R.color.connected)
            } else {
                binding.btnConnect.text = getString(R.string.btn_connect)
                binding.btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.disconnected)
                binding.tvConnectionStatus.text = getString(R.string.status_disconnected)
                binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.disconnected))
                setStatusDotColor(R.color.disconnected)
            }
        }
    }

    private fun setConnectingUi() {
        runOnUiThread {
            binding.btnConnect.text = "…"
            binding.btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.connecting)
            binding.tvConnectionStatus.text = getString(R.string.status_connecting)
            binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.connecting))
            setStatusDotColor(R.color.connecting)
        }
    }

    private fun setStatusDotColor(colorResId: Int) {
        val drawable = binding.statusDot.background.mutate()
        drawable.setTint(ContextCompat.getColor(this, colorResId))
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val timestamp = dateFormat.format(Date())
            val current = binding.tvLog.text.toString()
            val newLine = "[$timestamp] $message"
            binding.tvLog.text = if (current.isEmpty()) newLine else "$current\n$newLine"
        }
    }
}
