# Arquitetura e Estrutura de Diretórios — Blue Messenger

## 1. Padrão Arquitetural Adotado
O projeto adota **Clean Architecture + Hexagonal (Ports & Adapters)** sobre **Kotlin Multiplatform (KMP)** e **Compose Multiplatform** (ou UI compartilhada/modular), dividindo claramente:
- **Core Compartilhado (`shared / commonMain`)**: Domínio puro, protocolo de framing/serialização Bluetooth, casos de uso, gerenciamento de estado (MVI/MVVM) e persistência multiplataforma.
- **Adaptadores de Plataforma (`androidMain` e `jvmMain` para Linux)**: Implementações concretas do transporte Bluetooth (`android.bluetooth.BluetoothAdapter` no Android; BlueZ D-Bus / BlueCove / Socket nativo AF_BLUETOOTH no Linux) e armazenamento local.

```mermaid
flowchart TD
    subgraph UI["1. Apresentação (presentation/)"]
        Screens["Telas (Compose Multiplatform)"]
        VM["ViewModels (StateFlow)"]
    end

    subgraph Domain["2. Domínio (domain/)"]
        UC["Use Cases (Enviar, Receber, Parear)"]
        Model["Models (Message, Peer)"]
        
        subgraph Ports["Portas / Contratos"]
            PortCrypto["CryptoService (Interface)"]
            PortBT["BluetoothTransport (Interface)"]
            PortRepo["MessageRepository (Interface)"]
        end
    end

    subgraph Infra["3. Infraestrutura"]
        Crypto["crypto/ (Criptografia E2EE)"]
        Data["data/ (Banco SQLite)"]
        BT_Android["transport/ (Bluetooth Android)"]
        BT_Linux["transport/ (Bluetooth Linux)"]
    end

    Screens --> VM
    VM --> UC
    UC --> Model
    UC --> PortCrypto
    UC --> PortBT
    UC --> PortRepo

    Crypto -. implementa .-> PortCrypto
    Data -. implementa .-> PortRepo
    BT_Android -. implementa .-> PortBT
    BT_Linux -. implementa .-> PortBT
```

---

## 2. Mapeamento Explícito de Diretórios

```text
blue-messenger/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── docs/                                  # Documentação viva do projeto
│   ├── ARCHITECTURE.md
│   ├── CODING_GUIDELINES.md
│   ├── SPECIFICATION.md
│   ├── SYSTEM_STATE.md
│   └── TEMPLATE_TAREFA.md
├── shared/                                # Módulo KMP principal (Lógica, Protocolo, Domínio e UI compartilhada)
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/com/bluemessenger/
│       │   ├── domain/
│       │   │   ├── model/                 # Entidades imutáveis de negócio (Peer, Conversation, Message, LocalIdentity)
│       │   │   ├── primitives/            # Primitivas de domínio / VOs imutáveis (BluetoothAddress, DomainId, Instant, PublicKey)
│       │   │   ├── enums/                 # Enums e máquinas de estado (SessionState, MessageStatus, MessageDirection)
│       │   │   ├── error/                 # Erros e exceções do domínio (DomainError, DomainException)
│       │   │   ├── concurrency/           # Abstração de threads para testes (CoroutineDispatchers)
│       │   │   ├── ports/                 # Portas (interfaces/contratos) do domínio
│       │   │   │   ├── crypto/            # Portas criptográficas (CryptoService, KeyStoreProvider, HandshakeStepResult)
│       │   │   │   ├── transport/         # Portas de Bluetooth e constantes (BluetoothTransport, BluetoothScanner, TransportConstants)
│       │   │   │   └── repository/        # Interfaces de repositório (MessageRepository, PeerRepository, IdentityRepository)
│       │   │   └── usecase/               # Casos de uso de negócio
│       │   ├── crypto/                    # Motor Criptográfico Concreto (DefaultCryptoService, NoiseXxHandshake, DoubleRatchetSession, ChaCha20Poly1305Cipher, SafetyNumberGenerator)
│       │   │   ├── model/                 # Modelos criptográficos (IdentityKeyPair, EphemeralKeyPair, RatchetHeader)
│       │   │   ├── constants/             # Constantes criptográficas (CryptoConstants)
│       │   │   └── util/                  # Utilitários criptográficos (ByteArrayZeroizer, KdfChain, expect CryptoPrimitives)
│       │   ├── protocol/                  # Wire Protocol & Framing (PacketCodec, FrameEncoder, FrameDecoder)
│       │   │   ├── model/                 # Modelos imutáveis de pacotes (Packet, Handshake, EncryptedPayload, Ack, KeepAlive)
│       │   │   ├── util/                  # Utilitários de apoio binário (Crc32Checksum, BigEndianBytes)
│       │   │   ├── constants/             # Constantes de protocolo e opcodes (FrameConstants, PacketConstants)
│       │   │   └── error/                 # Erros tipados de framing e pacote (FrameError, PacketError)
│       │   ├── data/
│       │   │   ├── repository/            # Implementações de repositório (SQLDelight / InMemory)
│       │   │   └── mapper/                # Mappers entre entidades de banco/rede e domínio
│       │   └── presentation/
│       │       ├── chat/                  # State, Intent/Event e ViewModel/Store da conversa
│       │       └── discovery/             # State, Intent/Event e ViewModel/Store de busca de pares
│       ├── commonTest/kotlin/com/bluemessenger/
│       │   ├── domain/                    # Testes unitários de regras de negócio e use cases
│       │   ├── protocol/                  # Testes de framing, fragmentação e serialização
│       │   └── crypto/                    # Testes de AEAD, Noise XX, Double Ratchet e Safety Numbers
│       ├── jvmCommonMain/kotlin/com/bluemessenger/ # Source set compartilhado entre androidMain e jvmMain (evita duplicação JVM)
│       │   └── crypto/util/               # Implementação actual única de CryptoPrimitives via BouncyCastle Lightweight
│       ├── androidMain/kotlin/com/bluemessenger/
│       │   ├── crypto/                    # AndroidKeyStoreProvider
│       │   ├── transport/android/         # Implementação RFCOMM/BLE com android.bluetooth.*
│       │   └── di/                        # Wiring de dependências específicas do Android
│       ├── androidUnitTest/kotlin/com/bluemessenger/
│       ├── jvmMain/kotlin/com/bluemessenger/
│       │   ├── crypto/                    # LinuxSecretServiceKeyStoreProvider
│       │   ├── transport/linux/           # Implementação Bluetooth no Linux (BlueZ / DBus / Native Socket)
│       │   └── di/                        # Wiring de dependências específicas do Linux Desktop
│       └── jvmTest/kotlin/com/bluemessenger/
├── androidApp/                            # Entrypoint Android (Activity, AndroidManifest, Permissões)
│   ├── build.gradle.kts
│   └── src/main/
└── desktopApp/                            # Entrypoint Linux Desktop (MainKt, janela Compose Desktop / CLI)
    ├── build.gradle.kts
    └── src/jvmMain/kotlin/com/bluemessenger/desktop/
```

---

## 3. Diagrama do Modelo de Domínio (Domain Model)

Em consonância com as práticas de *Domain-Driven Design (DDD)* e *UML Distilled (Martin Fowler)*, o diagrama estrutural abaixo foca estritamente na **Perspectiva Conceitual e Entidades de Domínio** (`com.bluemessenger.domain.model`). Ele expressa a Linguagem Ubíqua, os estados do ciclo de vida e as cardinalidades de negócio do Blue Messenger, sem poluição de controladores, codecs ou adaptadores técnicos:

```mermaid
classDiagram
    direction LR

    class Peer {
        +UUID id
        +String nickname
        +BluetoothAddress address
        +PublicKey identityKey
        +Boolean isVerified
        +Instant lastSeenAt
    }

    class Conversation {
        +UUID id
        +Peer peer
        +SessionState state
        +Instant updatedAt
    }

    class Message {
        +UUID id
        +UUID conversationId
        +String plaintext
        +MessageDirection direction
        +MessageStatus status
        +Instant sentAt
        +Instant deliveredAt
    }

    class MessageStatus {
        <<enumeration>>
        PENDING
        SENT
        DELIVERED
        FAILED
    }

    class MessageDirection {
        <<enumeration>>
        INCOMING
        OUTGOING
    }

    class SessionState {
        <<enumeration>>
        DISCONNECTED
        CONNECTING
        CONNECTED_ENCRYPTED
    }

    Peer "1" <-- "1" Conversation : possui
    Conversation "1" --> "*" Message : contém
    Message --> MessageStatus : status
    Message --> MessageDirection : direction
    Conversation --> SessionState : state
```

---

## 4. Limites entre Camadas e Regras de Dependência

1. **`domain` não depende de ninguém**:
   - O pacote `com.bluemessenger.domain.*` contém apenas **Entidades**, **Casos de Uso** e **Portas (Interfaces)**.
   - Pode importar apenas a biblioteca padrão do Kotlin (`kotlin.*`) e `kotlinx.coroutines.*`.
   - É estritamente proibido importar `android.*`, bibliotecas de banco de dados, bibliotecas externas de criptografia ou frameworks de UI dentro de `domain`.
2. **`crypto`, `data` e `transport` implementam as portas definidas no `domain`**:
   - `crypto/` implementa o contrato `CryptoService` (utilizando Double Ratchet, X25519 e ChaCha20-Poly1305).
   - `data/` implementa `MessageRepository` e `PeerRepository` (utilizando SQLDelight/SQLite).
   - `androidMain` e `jvmMain` implementam `BluetoothTransport` para Android SDK e Linux BlueZ respectivamente.
3. **`protocol` é a camada de transporte e framing de linha**:
   - Responsável pela codificação/decodificação de frames binários com prefixo de tamanho e validação de integridade CRC32 consumidos pelos adaptadores de rede.
4. **`presentation` conversa apenas com `domain`**:
   - ViewModels/StateHolders consomem Use Cases e expõem estados imutáveis (`StateFlow<UiState>`) para as telas Compose Multiplatform.
