# Diretrizes e Boas Práticas de Codificação (Coding Guidelines — Kotlin Multiplatform)

**Versão:** 1.0  
**Data:** Novembro de 2026  
**Escopo:** Kotlin 2.x, Kotlin Multiplatform (KMP), Kotlinx Coroutines, Kotlinx Serialization, SQLDelight, Android SDK / JVM Desktop (Linux)  
**Documentos Relacionados:** [ARCHITECTURE.md](file:///workspace/blue-messenger/docs/ARCHITECTURE.md), [SPECIFICATION.md](file:///workspace/blue-messenger/docs/SPECIFICATION.md) e [TEMPLATE_TAREFA.md](file:///workspace/blue-messenger/docs/TEMPLATE_TAREFA.md)

---

## 1. Visão Geral

Este documento define os padrões técnicos, convenções idiomáticas em **Kotlin Multiplatform**, tratamento de concorrência com **Coroutines & Flow**, modelagem de domínio imutável e boas práticas de arquitetura limpa para o projeto **Blue Messenger** (comunicação P2P Bluetooth entre Linux e Android).

---

## 2. Padrões de Código e Diretrizes Gerais

* **Idioma Obrigatório no Código (English Only):**
  * Todos os identificadores (**packages, classes, interfaces, objects, functions, properties, variables, enums, exceptions**) e chaves de protocolo de rede **devem ser estritamente em inglês**.
  * Termos misturados ("Portunglês") são estritamente proibidos.

* **Código Autoexplicativo e Comentários (Clean Code & KDoc):**
  * **Proibido o uso de comentários procedurais óbvios** (ex: `// connect to socket`, `// parse frame`).
  * O código deve ser legível por suas declarações, tipos descritivos e métodos com responsabilidade única.
  * Comentários são reservados para **KDoc em interfaces públicas de domínio/transporte** (`BluetoothTransport`, `BluetoothScanner`, contratos do protocolo binário), explicando o propósito, garantias de thread safety e exceções disparadas.

* **Kotlin Idiomático e Imutabilidade por Padrão:**
  * Uso de `val` por padrão; `var` deve ser restrito a casos justificáveis de baixa camada / buffers de streaming.
  * Modelos de domínio, pacotes de rede e estados de tela devem ser `data class` imutáveis ou `sealed interface` para modelagem de estados e eventos finitos.
  * Coleções expostas devem ser somente leitura (`List<T>`, `Set<T>`, `Map<K, V>`). Evite expor `MutableList` fora do encapsulamento interno da classe.

* **Tratamento Funcional de Erros (No Silent Failures):**
  * Operações de I/O de rede Bluetooth ou banco de dados devem retornar tipos que forçam o tratamento explícito de falhas, preferencialmente `Result<T>` ou uma `sealed interface DomainError`.
  * Nunca capture `Throwable` ou `Exception` de forma cega sem remapear para um erro de domínio ou relançar `CancellationException` (essencial para que o cancelamento de Coroutines funcione corretamente).

* **Proibição Estrita de Magic Numbers e Magic Literals:**
  * É expressamente proibido o uso de números mágicos literais (ex: `16`, `32`, `64`, `1024`, `5000`, `15000`) ou strings soltas (UUIDs de serviço, magic bytes de cabeçalho, constantes de canal de protocolo) espalhadas pelo código sem uma constante nomeada que explique semanticamente o seu propósito.
  * Todo valor numérico, tamanho de buffer, timeout de conexão/keep-alive, limite de retry, magic byte de frame ou parâmetro de algoritmo criptográfico deve ser declarado como `const val` em `UPPER_SNAKE_CASE`:
    - **Constantes de Protocolo e Frames:** Em `com.bluemessenger.protocol.frame` (ex: `FRAME_MAGIC_BYTE = 0x42.toByte()`, `MAX_FRAME_PAYLOAD_BYTES = 1024`, `CRC32_CHECKSUM_SIZE_BYTES = 4`).
    - **Constantes Criptográficas:** Em `com.bluemessenger.crypto` (ex: `TAG_LENGTH_BYTES = 16`, `NONCE_LENGTH_BYTES = 12`, `SYMMETRIC_KEY_LENGTH_BYTES = 32`).
    - **Constantes de Temporização / Rede:** Em `com.bluemessenger.domain` ou transporte (ex: `KEEP_ALIVE_INTERVAL_MS = 15_000L`, `KEEP_ALIVE_TIMEOUT_MS = 5_000L`, `MAX_RECONNECT_ATTEMPTS = 5`).
  * **Exceção:** Os números `0` e `1` são aceitos sem constante apenas quando usados como índices ou contadores triviais (ex: `for (i in 0 until list.size)` ou `items.isEmpty()` / `list.size == 0`).

* **Princípio Fail-Fast e Proibição de Fallbacks Silenciosos em Configurações Críticas:**
  * É proibido o uso de fallbacks silenciosos ou valores padrão (`?: defaultValue`, `runCatching { null }` engolindo erros) para chaves criptográficas, permissões de Bluetooth, parâmetros de frame inválidos, integridade de pacotes ou configurações essenciais de segurança e domínio.
  * Se um dado indispensável não for informado, a tag Poly1305 de autenticação falhar, ou o frame Bluetooth chegar truncado ou corrompido, a aplicação **deve falhar imediatamente (fail-fast)** lançando uma exceção de domínio explícita ou retornando `DomainError` tipado (`DecryptionFailedError`, `CorruptedFrameError`, `MissingBluetoothPermissionError`), alertando o chamador e registrando log estruturado. Fallbacks só são permitidos quando a ausência do valor fizer parte expressa do design opcional do requisito de negócio.
  * **Observabilidade e Tratamento de Erros Assíncronos:** Toda branch condicional que detecta uma falha de transporte ou reconexão DEVE registrar log de aviso (`logger.warn`) com contexto estruturado. É expressamente proibido ignorar jobs de coroutines ou deixar fluxos de I/O sem tratamento de erro (`.catch { ... }`), devendo toda falha ser registrada com `logger.error` incluindo exceção e parâmetros de contexto.

* **Simplicidade de Design e Proibição de Overengineering (KISS / YAGNI):**
  * **Proibição de Interfaces Especulativas de Implementação Única:** Não crie interfaces ou abstrações genéricas (ex: `CryptoEngineFactory`, `MessageTransformerStrategy`, `SessionStoreProvider`) quando houver apenas uma implementação concreta em uso no projeto. Injetar a classe concreta diretamente via construtor. Interfaces são estritamente reservadas para **portas de infraestrutura / fronteiras de plataforma** (ex: `BluetoothTransport`, `BluetoothScanner`, `KeyStoreProvider`) onde Android e Linux realmente possuem implementações distintas.
  * **Proibição de Factories Prematuras:** A instanciação direta ou injeção via construtor deve ser adotada por padrão. Não crie classes de fábrica (`*Factory`) sem polimorfismo dinâmico estritamente comprovado em tempo de execução.
  * **Proibição de Camadas Intermediárias Fantasma:** Não crie serviços, proxies ou DTOs intermediários que apenas repassam chamadas sem adicionar validação, agregação ou regra de negócio real.
  * **Foco Estrito no Escopo Atual (YAGNI):** Não implemente recursos especulativos "para o futuro" (como suporte hipotético a chamadas de áudio, compressão de vídeo ou múltiplos protocolos além de Bluetooth) que não façam parte expressa do [SPECIFICATION.md](file:///workspace/blue-messenger/docs/SPECIFICATION.md). Mantenha o código minimalista, direto e focado na entrega do mensageiro Bluetooth seguro.

* **Concorrência e Coroutines Estruturadas:**
  * **Proibição de `GlobalScope`**: Todo processamento concorrente deve estar vinculado a um `CoroutineScope` com ciclo de vida gerenciado (ex: `ViewModelScope`, `ServiceScope`).
  * **Injeção de Dispatchers**: Nunca utilize `Dispatchers.IO` ou `Dispatchers.Default` hardcoded diretamente nas regras de negócio; injete uma abstração (ex: `CoroutineDispatchers`) para viabilizar testes unitários determinísticos com `StandardTestDispatcher`.
  * Exponha fluxos de dados observáveis usando `Flow<T>`, `StateFlow<T>` (estados imutáveis) ou `SharedFlow<T>` (eventos únicos).

* **Logging Estruturado e Observabilidade:**
  * Uso de logging multiplataforma estruturado (ex: Kermit ou logging facade compartilhada).
  * Concatenações puras em `println()` ou `System.out.println()` são proibidas em código de produção.
  * Nunca logue dados sensíveis ou chaves criptográficas em nível de `INFO` ou superior.

* **Qualidade, Linters e Testes Obrigatórios:**
  * O código deve passar de forma limpa por checagens estáticas (`./gradlew ktlintCheck` ou `./gradlew detekt`).
  * Cada nova funcionalidade de domínio ou protocolo deve conter testes unitários em `shared/src/commonTest`.
  * Testes de integração de Bluetooth ou D-Bus/Linux devem ser isolados em módulos de teste ou suportar simulação em memória (fakes de transporte).
