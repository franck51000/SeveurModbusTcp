# ServeurModbusTcp

Utilitaire Modbus TCP en mode serveur — simule un serveur Modbus TCP sur le port 502 (configurable).

## Téléchargement (Windows 10)

L'exécutable Windows 10 (64-bit) est disponible directement dans ce dépôt :

```
serveur-modbus-tcp.exe
```

## Utilisation

### Lancer le serveur

**Windows (port 502, droits administrateur recommandés) :**
```
serveur-modbus-tcp.exe
```

**Windows (port alternatif, sans droits administrateur) :**
```
serveur-modbus-tcp.exe 5020
```

**Linux / macOS :**
```bash
go build -o serveur-modbus-tcp .
sudo ./serveur-modbus-tcp          # port 502
./serveur-modbus-tcp 5020          # port alternatif
```

> **Note :** Sur Windows, le port 502 peut nécessiter une règle de pare-feu (autoriser les connexions TCP entrantes sur le port 502).

### Interface de commandes interactive

Une fois le serveur lancé, les commandes suivantes sont disponibles :

| Commande | Description |
|---|---|
| `set hr <adresse> <valeur>` | Écrire un registre de maintien (Holding Register) |
| `set ir <adresse> <valeur>` | Écrire un registre d'entrée (Input Register) |
| `set co <adresse> <0\|1>` | Écrire une bobine (Coil) |
| `set di <adresse> <0\|1>` | Écrire une entrée discrète (Discrete Input) |
| `get hr <adresse>` | Lire un registre de maintien |
| `get ir <adresse>` | Lire un registre d'entrée |
| `get co <adresse>` | Lire une bobine |
| `get di <adresse>` | Lire une entrée discrète |
| `list hr [debut] [fin]` | Lister les registres de maintien |
| `list ir [debut] [fin]` | Lister les registres d'entrée |
| `list co [debut] [fin]` | Lister les bobines |
| `list di [debut] [fin]` | Lister les entrées discrètes |
| `help` | Afficher l'aide |
| `quit` / `exit` | Arrêter le serveur |

### Exemple

```
Serveur Modbus TCP démarré sur 0.0.0.0:502

> set hr 0 1234
HR[0] = 1234 (0x04D2)
> set hr 1 65535
HR[1] = 65535 (0xFFFF)
> get hr 0
HR[0] = 1234 (0x04D2)
> list hr 0 4
HR[0] = 1234 (0x04D2)
HR[1] = 65535 (0xFFFF)
HR[2] = 0 (0x0000)
HR[3] = 0 (0x0000)
HR[4] = 0 (0x0000)
> set co 0 1
CO[0] = 1
> quit
Arrêt du serveur...
```

## Codes de fonction Modbus supportés

| Code | Nom |
|---|---|
| FC01 | Read Coils |
| FC02 | Read Discrete Inputs |
| FC03 | Read Holding Registers |
| FC04 | Read Input Registers |
| FC05 | Write Single Coil |
| FC06 | Write Single Holding Register |
| FC15 | Write Multiple Coils |
| FC16 | Write Multiple Holding Registers |

## Compilation depuis les sources

Prérequis : [Go 1.18+](https://go.dev/dl/)

```bash
# Linux / macOS
go build -o serveur-modbus-tcp .

# Windows (cross-compilation depuis Linux/macOS)
GOOS=windows GOARCH=amd64 go build -o serveur-modbus-tcp.exe .
```
