# SeveurModbusTcp – Modbus TCP Client Android

Application Android pour la lecture et l'écriture de registres Modbus TCP/IP v4.

## Fonctionnalités

- **Librairie Modbus TCP** complète implémentée en Kotlin :
  - FC01 – Read Coils
  - FC02 – Read Discrete Inputs
  - FC03 – Read Holding Registers
  - FC04 – Read Input Registers
  - FC05 – Write Single Coil
  - FC06 – Write Single Register
  - FC15 – Write Multiple Coils
  - FC16 – Write Multiple Registers
  - FC22 – Mask Write Register
  - FC23 – Read/Write Multiple Registers
- **Bouton de connexion coloré** (rouge = déconnecté, orange = connexion, vert = connecté)
- **Bouton Lire** et **Bouton Écrire**
- **Journal de connexion** en temps réel
- **Tableau des registres lus** avec toutes les valeurs
- **Affichage Décimal / Hexadécimal / Binaire**
- **Champ nombre de registres** à lire
- **Mode cyclique** avec intervalle configurable

## Téléchargement

Le fichier APK est disponible dans le dossier [`releases/`](releases/) ou dans les [Releases GitHub](../../releases).

## Build

### Via Android Studio / Gradle

```bash
./gradlew assembleDebug
```

L'APK sera généré dans `app/build/outputs/apk/debug/`.

### Configuration requise

- Android 8.0+ (API 26+)
- Java 17
- Android SDK 35

## Architecture

```
app/src/main/java/com/example/modbustcp/
├── modbus/
│   ├── ModbusClient.kt        # Client TCP Modbus
│   ├── ModbusException.kt     # Gestion des erreurs Modbus
│   ├── ModbusFunctionCode.kt  # Codes de fonction Modbus
│   └── ModbusResponse.kt      # Modèle de réponse
└── ui/
    └── MainActivity.kt        # Interface utilisateur
```

## Releases

- **v1.0** – Première version, toutes les fonctions Modbus implémentées
