// Modbus TCP Server Simulator — Version 2
// Simulates a Modbus TCP server on port 502 (configurable).
// Supports reading and writing Coils, Discrete Inputs, Input Registers and Holding Registers.
// Interactive CLI allows modifying register values at runtime.
// Version 2: adds configurable Unit ID parameter (0–255).
package main

import (
	"bufio"
	"encoding/binary"
	"flag"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
)

// ---------------------------------------------------------------------------
// Modbus data store
// ---------------------------------------------------------------------------

const (
	maxCoils     = 65536
	maxRegisters = 65536
)

// Store holds all Modbus data tables protected by a mutex.
type Store struct {
	mu               sync.RWMutex
	coils            [maxCoils]bool       // FC01 / FC05 / FC15
	discreteInputs   [maxCoils]bool       // FC02
	holdingRegisters [maxRegisters]uint16 // FC03 / FC06 / FC16
	inputRegisters   [maxRegisters]uint16 // FC04
}

var store Store

// configuredUnitID is the Unit ID this server responds to (0–255).
// Requests with a different Unit ID are rejected with exception 0x0A.
var configuredUnitID byte

// ---------------------------------------------------------------------------
// Modbus TCP protocol constants
// ---------------------------------------------------------------------------

const (
	fcReadCoils              = 0x01
	fcReadDiscreteInputs     = 0x02
	fcReadHoldingRegisters   = 0x03
	fcReadInputRegisters     = 0x04
	fcWriteSingleCoil        = 0x05
	fcWriteSingleRegister    = 0x06
	fcWriteMultipleCoils     = 0x0F
	fcWriteMultipleRegisters = 0x10
)

const mbapHeaderLen = 7 // Transaction(2) + Protocol(2) + Length(2) + UnitID(1)

// ---------------------------------------------------------------------------
// Request / Response helpers
// ---------------------------------------------------------------------------

// readRequest reads one complete Modbus TCP frame from conn.
// Returns the raw PDU bytes (function code + data).
func readRequest(conn net.Conn) (txID uint16, unitID byte, pdu []byte, err error) {
	header := make([]byte, mbapHeaderLen)
	if _, err = readFull(conn, header); err != nil {
		return
	}
	txID = binary.BigEndian.Uint16(header[0:2])
	// header[2:4] = protocol id (must be 0)
	length := binary.BigEndian.Uint16(header[4:6])
	unitID = header[6]
	if length < 2 {
		err = fmt.Errorf("invalid PDU length %d", length)
		return
	}
	pdu = make([]byte, length-1) // length includes unitID byte
	_, err = readFull(conn, pdu)
	return
}

// sendResponse builds and writes a Modbus TCP response frame.
func sendResponse(conn net.Conn, txID uint16, unitID byte, pdu []byte) error {
	frame := make([]byte, mbapHeaderLen+len(pdu))
	binary.BigEndian.PutUint16(frame[0:2], txID)
	binary.BigEndian.PutUint16(frame[2:4], 0) // protocol id
	binary.BigEndian.PutUint16(frame[4:6], uint16(1+len(pdu)))
	frame[6] = unitID
	copy(frame[7:], pdu)
	_, err := conn.Write(frame)
	return err
}

// sendException sends a Modbus exception response.
func sendException(conn net.Conn, txID uint16, unitID byte, fc byte, exCode byte) error {
	return sendResponse(conn, txID, unitID, []byte{fc | 0x80, exCode})
}

// readFull reads exactly len(buf) bytes.
func readFull(conn net.Conn, buf []byte) (int, error) {
	total := 0
	for total < len(buf) {
		n, err := conn.Read(buf[total:])
		total += n
		if err != nil {
			return total, err
		}
	}
	return total, nil
}

// ---------------------------------------------------------------------------
// Function code handlers
// ---------------------------------------------------------------------------

func handleFC01(pdu []byte) ([]byte, byte) {
	if len(pdu) < 5 {
		return nil, 0x03
	}
	addr := binary.BigEndian.Uint16(pdu[1:3])
	count := binary.BigEndian.Uint16(pdu[3:5])
	if count < 1 || count > 2000 {
		return nil, 0x03
	}
	if int(addr)+int(count) > maxCoils {
		return nil, 0x02
	}
	byteCount := (count + 7) / 8
	resp := make([]byte, 2+byteCount)
	resp[0] = fcReadCoils
	resp[1] = byte(byteCount)
	store.mu.RLock()
	for i := uint16(0); i < count; i++ {
		if store.coils[addr+i] {
			resp[2+i/8] |= 1 << (i % 8)
		}
	}
	store.mu.RUnlock()
	return resp, 0
}

func handleFC02(pdu []byte) ([]byte, byte) {
	if len(pdu) < 5 {
		return nil, 0x03
	}
	addr := binary.BigEndian.Uint16(pdu[1:3])
	count := binary.BigEndian.Uint16(pdu[3:5])
	if count < 1 || count > 2000 {
		return nil, 0x03
	}
	if int(addr)+int(count) > maxCoils {
		return nil, 0x02
	}
	byteCount := (count + 7) / 8
	resp := make([]byte, 2+byteCount)
	resp[0] = fcReadDiscreteInputs
	resp[1] = byte(byteCount)
	store.mu.RLock()
	for i := uint16(0); i < count; i++ {
		if store.discreteInputs[addr+i] {
			resp[2+i/8] |= 1 << (i % 8)
		}
	}
	store.mu.RUnlock()
	return resp, 0
}

func handleFC03(pdu []byte) ([]byte, byte) {
	if len(pdu) < 5 {
		return nil, 0x03
	}
	addr := binary.BigEndian.Uint16(pdu[1:3])
	count := binary.BigEndian.Uint16(pdu[3:5])
	if count < 1 || count > 125 {
		return nil, 0x03
	}
	if int(addr)+int(count) > maxRegisters {
		return nil, 0x02
	}
	byteCount := count * 2
	resp := make([]byte, 2+byteCount)
	resp[0] = fcReadHoldingRegisters
	resp[1] = byte(byteCount)
	store.mu.RLock()
	for i := uint16(0); i < count; i++ {
		binary.BigEndian.PutUint16(resp[2+i*2:], store.holdingRegisters[addr+i])
	}
	store.mu.RUnlock()
	return resp, 0
}

func handleFC04(pdu []byte) ([]byte, byte) {
	if len(pdu) < 5 {
		return nil, 0x03
	}
	addr := binary.BigEndian.Uint16(pdu[1:3])
	count := binary.BigEndian.Uint16(pdu[3:5])
	if count < 1 || count > 125 {
		return nil, 0x03
	}
	if int(addr)+int(count) > maxRegisters {
		return nil, 0x02
	}
	byteCount := count * 2
	resp := make([]byte, 2+byteCount)
	resp[0] = fcReadInputRegisters
	resp[1] = byte(byteCount)
	store.mu.RLock()
	for i := uint16(0); i < count; i++ {
		binary.BigEndian.PutUint16(resp[2+i*2:], store.inputRegisters[addr+i])
	}
	store.mu.RUnlock()
	return resp, 0
}

func handleFC05(pdu []byte) ([]byte, byte) {
	if len(pdu) < 5 {
		return nil, 0x03
	}
	addr := binary.BigEndian.Uint16(pdu[1:3])
	value := binary.BigEndian.Uint16(pdu[3:5])
	if value != 0x0000 && value != 0xFF00 {
		return nil, 0x03
	}
	store.mu.Lock()
	store.coils[addr] = value == 0xFF00
	store.mu.Unlock()
	resp := make([]byte, 5)
	resp[0] = fcWriteSingleCoil
	copy(resp[1:], pdu[1:5])
	return resp, 0
}

func handleFC06(pdu []byte) ([]byte, byte) {
	if len(pdu) < 5 {
		return nil, 0x03
	}
	addr := binary.BigEndian.Uint16(pdu[1:3])
	value := binary.BigEndian.Uint16(pdu[3:5])
	store.mu.Lock()
	store.holdingRegisters[addr] = value
	store.mu.Unlock()
	resp := make([]byte, 5)
	resp[0] = fcWriteSingleRegister
	copy(resp[1:], pdu[1:5])
	return resp, 0
}

func handleFC15(pdu []byte) ([]byte, byte) {
	if len(pdu) < 6 {
		return nil, 0x03
	}
	addr := binary.BigEndian.Uint16(pdu[1:3])
	count := binary.BigEndian.Uint16(pdu[3:5])
	byteCount := pdu[5]
	if count < 1 || count > 1968 {
		return nil, 0x03
	}
	expected := (count + 7) / 8
	if int(byteCount) < int(expected) || len(pdu) < 6+int(byteCount) {
		return nil, 0x03
	}
	if int(addr)+int(count) > maxCoils {
		return nil, 0x02
	}
	store.mu.Lock()
	for i := uint16(0); i < count; i++ {
		b := pdu[6+i/8]
		store.coils[addr+i] = (b>>uint(i%8))&1 == 1
	}
	store.mu.Unlock()
	resp := make([]byte, 5)
	resp[0] = fcWriteMultipleCoils
	binary.BigEndian.PutUint16(resp[1:3], addr)
	binary.BigEndian.PutUint16(resp[3:5], count)
	return resp, 0
}

func handleFC16(pdu []byte) ([]byte, byte) {
	if len(pdu) < 6 {
		return nil, 0x03
	}
	addr := binary.BigEndian.Uint16(pdu[1:3])
	count := binary.BigEndian.Uint16(pdu[3:5])
	byteCount := pdu[5]
	if count < 1 || count > 123 {
		return nil, 0x03
	}
	if int(byteCount) < int(count)*2 || len(pdu) < 6+int(byteCount) {
		return nil, 0x03
	}
	if int(addr)+int(count) > maxRegisters {
		return nil, 0x02
	}
	store.mu.Lock()
	for i := uint16(0); i < count; i++ {
		store.holdingRegisters[addr+i] = binary.BigEndian.Uint16(pdu[6+i*2:])
	}
	store.mu.Unlock()
	resp := make([]byte, 5)
	resp[0] = fcWriteMultipleRegisters
	binary.BigEndian.PutUint16(resp[1:3], addr)
	binary.BigEndian.PutUint16(resp[3:5], count)
	return resp, 0
}

// ---------------------------------------------------------------------------
// Connection handler
// ---------------------------------------------------------------------------

func handleConn(conn net.Conn) {
	defer conn.Close()
	remote := conn.RemoteAddr().String()
	fmt.Printf("[+] Connexion: %s\n", remote)
	for {
		txID, unitID, pdu, err := readRequest(conn)
		if err != nil {
			fmt.Printf("[-] Déconnexion: %s (%v)\n", remote, err)
			return
		}
		// Reject requests addressed to a different Unit ID (0xFF is accepted as
		// a wildcard used by some Modbus TCP masters for direct connections).
		if unitID != configuredUnitID && unitID != 0xFF {
			if len(pdu) > 0 {
				sendException(conn, txID, unitID, pdu[0], 0x0A) // Gateway Path Unavailable
			}
			continue
		}
		if len(pdu) == 0 {
			sendException(conn, txID, unitID, 0, 0x01)
			continue
		}
		fc := pdu[0]
		var (
			resp   []byte
			exCode byte
		)
		switch fc {
		case fcReadCoils:
			resp, exCode = handleFC01(pdu)
		case fcReadDiscreteInputs:
			resp, exCode = handleFC02(pdu)
		case fcReadHoldingRegisters:
			resp, exCode = handleFC03(pdu)
		case fcReadInputRegisters:
			resp, exCode = handleFC04(pdu)
		case fcWriteSingleCoil:
			resp, exCode = handleFC05(pdu)
		case fcWriteSingleRegister:
			resp, exCode = handleFC06(pdu)
		case fcWriteMultipleCoils:
			resp, exCode = handleFC15(pdu)
		case fcWriteMultipleRegisters:
			resp, exCode = handleFC16(pdu)
		default:
			exCode = 0x01 // illegal function
		}
		if exCode != 0 {
			sendException(conn, txID, unitID, fc, exCode)
		} else {
			sendResponse(conn, txID, unitID, resp)
		}
	}
}

// ---------------------------------------------------------------------------
// Interactive CLI
// ---------------------------------------------------------------------------

func printHelp() {
	fmt.Println()
	fmt.Println("Commandes disponibles:")
	fmt.Println("  set hr <adresse> <valeur>   - Écrire un registre de maintien (Holding Register)")
	fmt.Println("  set ir <adresse> <valeur>   - Écrire un registre d'entrée (Input Register)")
	fmt.Println("  set co <adresse> <0|1>      - Écrire une bobine (Coil)")
	fmt.Println("  set di <adresse> <0|1>      - Écrire une entrée discrète (Discrete Input)")
	fmt.Println("  get hr <adresse>            - Lire un registre de maintien")
	fmt.Println("  get ir <adresse>            - Lire un registre d'entrée")
	fmt.Println("  get co <adresse>            - Lire une bobine")
	fmt.Println("  get di <adresse>            - Lire une entrée discrète")
	fmt.Println("  list hr [debut] [fin]       - Lister les registres de maintien")
	fmt.Println("  list ir [debut] [fin]       - Lister les registres d'entrée")
	fmt.Println("  list co [debut] [fin]       - Lister les bobines")
	fmt.Println("  list di [debut] [fin]       - Lister les entrées discrètes")
	fmt.Println("  help                        - Afficher cette aide")
	fmt.Println("  quit / exit                 - Arrêter le serveur")
	fmt.Println()
}

func parseAddr(s string) (uint16, error) {
	v, err := strconv.ParseUint(s, 10, 16)
	if err != nil {
		return 0, fmt.Errorf("adresse invalide: %s", s)
	}
	return uint16(v), nil
}

func runCLI(done chan struct{}) {
	scanner := bufio.NewScanner(os.Stdin)
	printHelp()
	fmt.Print("> ")
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			fmt.Print("> ")
			continue
		}
		parts := strings.Fields(line)
		cmd := strings.ToLower(parts[0])

		switch cmd {
		case "quit", "exit":
			fmt.Println("Arrêt du serveur...")
			close(done)
			return

		case "help":
			printHelp()

		case "set":
			if len(parts) < 4 {
				fmt.Println("Usage: set <hr|ir|co|di> <adresse> <valeur>")
				break
			}
			table := strings.ToLower(parts[1])
			addr, err := parseAddr(parts[2])
			if err != nil {
				fmt.Println(err)
				break
			}
			switch table {
			case "hr":
				v, err := strconv.ParseUint(parts[3], 0, 16)
				if err != nil {
					fmt.Printf("Valeur invalide: %s\n", parts[3])
					break
				}
				store.mu.Lock()
				store.holdingRegisters[addr] = uint16(v)
				store.mu.Unlock()
				fmt.Printf("HR[%d] = %d (0x%04X)\n", addr, uint16(v), uint16(v))
			case "ir":
				v, err := strconv.ParseUint(parts[3], 0, 16)
				if err != nil {
					fmt.Printf("Valeur invalide: %s\n", parts[3])
					break
				}
				store.mu.Lock()
				store.inputRegisters[addr] = uint16(v)
				store.mu.Unlock()
				fmt.Printf("IR[%d] = %d (0x%04X)\n", addr, uint16(v), uint16(v))
			case "co":
				v, err := strconv.ParseUint(parts[3], 10, 8)
				if err != nil || (v != 0 && v != 1) {
					fmt.Println("Valeur invalide: utiliser 0 ou 1")
					break
				}
				store.mu.Lock()
				store.coils[addr] = v == 1
				store.mu.Unlock()
				fmt.Printf("CO[%d] = %d\n", addr, v)
			case "di":
				v, err := strconv.ParseUint(parts[3], 10, 8)
				if err != nil || (v != 0 && v != 1) {
					fmt.Println("Valeur invalide: utiliser 0 ou 1")
					break
				}
				store.mu.Lock()
				store.discreteInputs[addr] = v == 1
				store.mu.Unlock()
				fmt.Printf("DI[%d] = %d\n", addr, v)
			default:
				fmt.Println("Type inconnu. Utiliser: hr, ir, co, di")
			}

		case "get":
			if len(parts) < 3 {
				fmt.Println("Usage: get <hr|ir|co|di> <adresse>")
				break
			}
			table := strings.ToLower(parts[1])
			addr, err := parseAddr(parts[2])
			if err != nil {
				fmt.Println(err)
				break
			}
			store.mu.RLock()
			switch table {
			case "hr":
				v := store.holdingRegisters[addr]
				fmt.Printf("HR[%d] = %d (0x%04X)\n", addr, v, v)
			case "ir":
				v := store.inputRegisters[addr]
				fmt.Printf("IR[%d] = %d (0x%04X)\n", addr, v, v)
			case "co":
				v := store.coils[addr]
				fmt.Printf("CO[%d] = %v\n", addr, v)
			case "di":
				v := store.discreteInputs[addr]
				fmt.Printf("DI[%d] = %v\n", addr, v)
			default:
				fmt.Println("Type inconnu. Utiliser: hr, ir, co, di")
			}
			store.mu.RUnlock()

		case "list":
			if len(parts) < 2 {
				fmt.Println("Usage: list <hr|ir|co|di> [debut] [fin]")
				break
			}
			table := strings.ToLower(parts[1])
			start := uint16(0)
			end := uint16(19)
			if len(parts) >= 3 {
				v, err := parseAddr(parts[2])
				if err != nil {
					fmt.Println(err)
					break
				}
				start = v
				end = start + 19
			}
			if len(parts) >= 4 {
				v, err := parseAddr(parts[3])
				if err != nil {
					fmt.Println(err)
					break
				}
				end = v
			}
			if end < start {
				end = start
			}
			store.mu.RLock()
			switch table {
			case "hr":
				for i := int(start); i <= int(end); i++ {
					v := store.holdingRegisters[i]
					fmt.Printf("HR[%d] = %d (0x%04X)\n", i, v, v)
				}
			case "ir":
				for i := int(start); i <= int(end); i++ {
					v := store.inputRegisters[i]
					fmt.Printf("IR[%d] = %d (0x%04X)\n", i, v, v)
				}
			case "co":
				for i := int(start); i <= int(end); i++ {
					fmt.Printf("CO[%d] = %v\n", i, store.coils[i])
				}
			case "di":
				for i := int(start); i <= int(end); i++ {
					fmt.Printf("DI[%d] = %v\n", i, store.discreteInputs[i])
				}
			default:
				fmt.Println("Type inconnu. Utiliser: hr, ir, co, di")
			}
			store.mu.RUnlock()

		default:
			fmt.Printf("Commande inconnue: %s (tapez 'help' pour l'aide)\n", cmd)
		}
		fmt.Print("> ")
	}
	// stdin closed (e.g. EOF) without explicit quit: keep server running until signal
	fmt.Println("(stdin fermé — serveur toujours actif, envoyez SIGINT pour arrêter)")
	select {}
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

func main() {
	portFlag := flag.Int("port", 502, "Port TCP d'écoute (0–65535)")
	unitIDFlag := flag.Int("unit-id", 1, "Unit ID Modbus (0–255)")
	flag.Parse()

	if *portFlag < 0 || *portFlag > 65535 {
		fmt.Fprintln(os.Stderr, "Erreur: le port doit être compris entre 0 et 65535")
		os.Exit(1)
	}
	if *unitIDFlag < 0 || *unitIDFlag > 255 {
		fmt.Fprintln(os.Stderr, "Erreur: le Unit ID doit être compris entre 0 et 255")
		os.Exit(1)
	}

	configuredUnitID = byte(*unitIDFlag)
	port := strconv.Itoa(*portFlag)

	addr := "0.0.0.0:" + port
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Erreur: impossible d'écouter sur %s: %v\n", addr, err)
		fmt.Fprintf(os.Stderr, "Conseil: Sur Linux/Mac, le port 502 nécessite les droits root.\n")
		fmt.Fprintf(os.Stderr, "         Utilisez: sudo ./serveur-modbus-tcp-v2  ou  ./serveur-modbus-tcp-v2 -port 5020\n")
		os.Exit(1)
	}
	fmt.Println("ServeurModbusTcp — Version 2")
	fmt.Printf("Serveur Modbus TCP démarré sur %s\n", addr)
	fmt.Printf("Unit ID: %d\n", configuredUnitID)

	done := make(chan struct{})

	// Accept connections in background
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				select {
				case <-done:
					return
				default:
					fmt.Printf("Erreur accept: %v\n", err)
					continue
				}
			}
			go handleConn(conn)
		}
	}()

	// Interactive CLI blocks until quit/exit
	runCLI(done)
	ln.Close()
}
