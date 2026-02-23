# ServeurModbusTcp

Utilitaire Modbus TCP en mode serveur — simule un serveur Modbus TCP sur le port 502 (configurable).

## Version 2 — Nouveautés

- Ajout du paramètre **Unit ID** (`-unit-id`, valeur 0–255, défaut : 1).  
  Le serveur répond uniquement aux requêtes Modbus adressées à cet identifiant.  
  Les requêtes avec un Unit ID différent reçoivent l'exception Modbus `0x0A` (Gateway Path Unavailable).  
  La valeur `0xFF` est également acceptée (wildcard Modbus TCP standard).
- Tous les paramètres sont désormais passés sous forme de **flags** nommés (`-port`, `-unit-id`).

## Téléchargement (Windows 10/11, 64-bit)

L'exécutable Windows 64-bit est disponible directement dans ce dépôt :

```
serveur-modbus-tcp-v2.exe
```

## Utilisation

### Lancer le serveur

**Windows — valeurs par défaut (port 502, Unit ID 1) :**
```
serveur-modbus-tcp-v2.exe
```

**Windows — port et Unit ID personnalisés :**
```
serveur-modbus-tcp-v2.exe -port 5020 -unit-id 10
```

**Linux / macOS :**
```bash
go build -o serveur-modbus-tcp-v2 .
sudo ./serveur-modbus-tcp-v2                        # port 502, Unit ID 1
./serveur-modbus-tcp-v2 -port 5020 -unit-id 10      # port et Unit ID personnalisés
```

**Afficher l'aide :**
```
serveur-modbus-tcp-v2.exe -help
```

> **Note :** Sur Windows, le port 502 peut nécessiter une règle de pare-feu (autoriser les connexions TCP entrantes sur le port 502).

### Paramètres de lancement

| Paramètre | Défaut | Description |
|---|---|---|
| `-port <n>` | `502` | Port TCP d'écoute (0–65535) |
| `-unit-id <n>` | `1` | Unit ID Modbus (0–255) |

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
ServeurModbusTcp — Version 2
Serveur Modbus TCP démarré sur 0.0.0.0:5020
Unit ID: 10

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
go build -o serveur-modbus-tcp-v2 .

# Windows (cross-compilation depuis Linux/macOS)
GOOS=windows GOARCH=amd64 go build -o serveur-modbus-tcp-v2.exe .
```
