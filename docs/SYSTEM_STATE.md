# Estado Atual do Sistema (System State) — Blue Messenger

> **Documento Vivo**: Mantido automaticamente pelas skills do Kanban (`task-close`, `task-plan`).  
> **Propósito**: Servir como memória executiva compacta para agentes e humanos, garantindo que o estado real do código seja consultado rapidamente sem consumir grandes volumes de tokens.

---

## 1. Status do Kanban de Desenvolvimento
- **Cards Concluídos (`done/`)**:
  - `001-setup-kmp-e-dominio-core.md` — Setup do Projeto KMP, Modelos de Domínio e Portas (`domain`)
  - `002-protocolo-framing-binario-e-pacotes.md` — Protocolo Binário Length-Prefixed, CRC32 e Pacotes (`protocol`)
  - `003-motor-criptografico-e2ee-double-ratchet.md` — Motor Criptográfico E2EE: Noise XX, Double Ratchet, AEAD e Safety Numbers (`crypto`)
- **Card Atual em Andamento (`doing/`)**:
  - Nenhum no momento.
- **Card em Revisão (`review/`)**:
  - Nenhum no momento.
- **Cards Planejados no Pipeline (`prototype/`)**:
  - `004-persistencia-local-sqldelight-repositorios.md` — Persistência Local Multiplataforma com SQLDelight (`data`)
  - `005-casos-de-uso-sessao-mensagens-e-keepalive.md` — Casos de Uso de Domínio: Sessão E2EE, Mensagens, Fila Offline e Keep-Alive (`domain/usecase`)
  - `006-adaptador-bluetooth-keystore-linux.md` — Adaptadores Linux Desktop (`jvmMain`): Transporte Bluetooth RFCOMM/BlueZ e KeyStore Seguro
  - `007-adaptador-bluetooth-keystore-android.md` — Adaptadores Android (`androidMain`): Transporte Bluetooth RFCOMM, Permissões e Android KeyStore
  - `008-apresentacao-viewmodels-e-ui-compartilhada.md` — Camada de Apresentação (`presentation`): ViewModels StateFlow e Telas Compose Multiplatform
  - `009-entrypoints-androidapp-e-desktopapp.md` — Entrypoints de Plataforma (`androidApp` e `desktopApp`) e Integração Ponta a Ponta

---

## 2. Módulos e Bounded Contexts Ativos no Código

| Módulo / Pacote | Papel Principal | Componentes Chave |
| :--- | :--- | :--- |
| **`domain`** (`com.bluemessenger.domain`) | Regras de negócio, modelos centrais, primitivas, enums e portas (100% puro `kotlin.*` e `kotlinx.coroutines.*`) | `model/` (`Peer`, `Conversation`, `Message`, `LocalIdentity`), `primitives/` (`BluetoothAddress`, `DomainId`, `Instant`, `PublicKey`), `enums/` (`SessionState`, `MessageStatus`, `MessageDirection`), `error/` (`DomainError`, `DomainException`), `concurrency/` (`CoroutineDispatchers`), `ports/transport/` (`BluetoothTransport`, `BluetoothScanner`, `TransportConstants`), `ports/crypto/` (`CryptoService`, `KeyStoreProvider`, `HandshakeStepResult`), `ports/repository/` (`MessageRepository`, `PeerRepository`, `IdentityRepository`) |
| **`crypto`** (`com.bluemessenger.crypto`) | Motor criptográfico E2EE estilo Signal/WhatsApp (Double Ratchet, X25519, AEAD) | `CryptoServiceImpl`, `CryptoHandshake`, `CryptoSession`, `CryptoCipher`, `CryptoSafetyNumber`, `CryptoEngine`, `model/` (`IdentityKeyPair`, `EphemeralKeyPair`, `RatchetHeader`), `constants/` (`CryptoConstants`), `util/` (`ByteArrayZeroizer`, `KdfChain`), + `jvmCommonMain/crypto/CryptoEngine` |
| **`protocol`** (`com.bluemessenger.protocol`) | Framing binário de rede, pacotes de handshake e integridade | `PacketCodec`, `FrameEncoder`, `FrameDecoder`, `model/` (`Packet`, `HandshakePacket`, `EncryptedPayloadPacket`, `AckPacket`, `KeepAlivePacket`), `util/` (`Crc32Checksum`, `BigEndianBytes`), `constants/` (`FrameConstants`, `PacketConstants`), `error/` (`FrameError`, `PacketError`) |
| **`data`** (`com.bluemessenger.data`) | Persistência local multiplataforma e mappers | `repository/` (`SqlDelightMessageRepository`), `mapper/` |
| **`presentation`** (`com.bluemessenger.presentation`) | State holders, ViewModels e estados de UI multiplataforma | `chat/` (`ChatViewModel`, `ChatUiState`), `discovery/` (`DiscoveryViewModel`, `DiscoveryUiState`) |
| **`platform-android`** (`com.bluemessenger.transport.android`) | Implementação do transporte Bluetooth usando APIs do Android SDK | `AndroidBluetoothTransport`, `AndroidBluetoothScanner`, `RfcommConnection` |
| **`platform-linux`** (`com.bluemessenger.transport.linux`) | Implementação do transporte Bluetooth no Linux (BlueZ / Sockets nativos) | `LinuxBluezTransport`, `LinuxBluetoothScanner`, `RfcommSocket` |

---

## 3. Persistência e Armazenamento Local

- **Engine de Banco de Dados**: SQLDelight (KMP) ou driver local SQLite.
- **Tabelas / Esquema Previsto**:
  - `peers`: `id` (TEXT PK), `name` (TEXT), `bluetooth_address` (TEXT UNIQUE), `last_seen_at` (INTEGER).
  - `messages`: `id` (TEXT PK), `peer_id` (TEXT FK), `content` (TEXT), `direction` (TEXT: INCOMING/OUTGOING), `status` (TEXT: PENDING/SENT/DELIVERED/FAILED), `timestamp` (INTEGER).

---

## 4. Log de Decisões Importantes (ADR Compacto)

- **ADR-001 (2026-11-17)**: Escolha de Kotlin Multiplatform (KMP) com foco em Linux e Android, garantindo que regras de negócio, serialização de pacotes de rede e persistência sejam 100% compartilhadas em `commonMain`.
- **ADR-002 (2026-11-17)**: Transporte Bluetooth baseado em RFCOMM stream com framing delimitado por tamanho (Length-Prefixed Framing) e CRC para evitar leituras incompletas ou corrupção de mensagens entre SOs diferentes.
- **ADR-003 (2026-11-17)**: Criptografia de Ponta a Ponta (E2EE) estilo Signal/WhatsApp adotando o algoritmo Double Ratchet com curvas elípticas Curve25519 (X25519 para troca de chaves Diffie-Hellman e Ed25519 para identidade) e ChaCha20-Poly1305 para cifragem autenticada (AEAD). Garante Forward Secrecy, Break-in Recovery e imunidade contra sniffing Bluetooth, com Safety Numbers (SAS) para validação contra MITM.
- **ADR-004 (2026-11-18)**: Setup base KMP configurado com Android `minSdk = 31` (Android 12 alinhado com permissões granulares de Bluetooth sem GPS), `compileSdk = 34`, `targetSdk = 34`, `JDK 17` (`jvmTarget = 17`), Kotlin 2.1.21, Coroutines 1.10.2 e análise estática dupla via `ktlint` (12.2.0) e `detekt` (1.23.8) com proibições estritas de magic numbers, println e swallowed exceptions.
- **ADR-005 (2026-11-19)**: Modelagem de domínio pura com zero dependências externas (`kotlin.*` e `kotlinx.coroutines.*` exclusivamente), adotando `@JvmInline value class` para primitivas de domínio e IDs temporais (RFC 9562 UUIDv7 puro e Instant temporal), e separação estrutural estrita: `model/` (entidades), `primitives/` (VOs imutáveis), `enums/` (máquinas de estado), `error/` (erros de domínio tipados), `concurrency/` (injeção de dispatchers) e `ports/` (`crypto`, `transport`, `repository`).
- **ADR-006 (2026-11-19)**: Serialização de pacotes de protocolo (`protocol/PacketCodec`) via Codec Binário Posicional Puro manual em Big-Endian (ordem de rede), eliminando dependências externas (como Protobuf ou CBOR) e minimizando o overhead de bytes sobre streams Bluetooth RFCOMM (RNF05), condicionado à cobertura obrigatória de testes automatizados exaustivos de round-trip e fail-fast contra corrupção/truncamento.
- **ADR-007 (2026-11-19)**: Estrutura do módulo `protocol` com API principal na raiz (`PacketCodec`, `FrameEncoder`, `FrameDecoder`) e subpacotes por papel (`model/`, `util/`, `constants/`, `error/`); separação por ciclo de vida entre `PacketCodec`/`FrameEncoder` (`object` *stateless* e *thread-safe*) e `FrameDecoder` (`class` *stateful* por stream RFCOMM com `reset()` e validação fail-fast de `FRAME_MAGIC_BYTE = 0x42`, `MAX_FRAME_PAYLOAD_BYTES = 1024` e CRC32 IEEE 802.3).
- **ADR-008 (2026-11-19)**: Adoção da Lightweight API do BouncyCastle (`org.bouncycastle:bcprov-jdk18on`) encapsulada em `internal expect/actual object CryptoEngine` no source set intermediário `jvmCommonMain` (`jvmMain` + `androidMain`), evitando conflitos JCA no Android 12 (`minSdk = 31`) e binários JNI, com derivação determinística de nonce (overhead de 56 B `<= MAX_CRYPTO_OVERHEAD_BYTES = 80`) e zeroização explícita (`ByteArrayZeroizer`).
- **ADR-009 (2026-11-19)**: Padronização coesa do módulo `crypto` no mesmo formato de `protocol/` (`CryptoServiceImpl` com `Mutex` por peer, `CryptoHandshake` com construtor direto `isInitiator`, `CryptoSession`, `CryptoCipher`, `CryptoSafetyNumber`, ponte `CryptoEngine` e subpacotes `model/`, `constants/`, `util/`), sem pastas de arquivo único nem factories redundantes.
