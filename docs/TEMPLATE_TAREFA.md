# NNN — Título curto

## Objetivo
Uma frase. Por que essa tarefa existe (o problema que resolve, não a solução).

## Contexto
- Onde vive no código: `shared/src/commonMain/kotlin/com/bluemessenger/<module>/...` (ou `androidMain` / `jvmMain`)
- Docs relevantes: `docs/SPECIFICATION.md` §X, `docs/ARCHITECTURE.md` §Y, `docs/CODING_GUIDELINES.md`
- Restrições / invariantes que precisam ser respeitados:
  -

## Escopo
- [ ] Passo concreto 1
- [ ] Passo concreto 2
- [ ] Testes (`commonTest`, `jvmTest` ou `androidUnitTest`)

## Fora de escopo
- Coisas que parecem próximas mas ficam pra outra tarefa. Explícito evita deriva.

## Critérios de aceite
- `./gradlew compileKotlinJvm compileDebugKotlinAndroid` limpo
- `./gradlew ktlintCheck` (ou `detekt`) limpo
- `./gradlew check` passa todos os testes automatizados
- (Outros específicos da tarefa)

## Notas de decisão
Espaço pra registrar escolhas tomadas durante a execução — ancora o "porquê" quando alguém olhar isso no futuro. Preencher só se houve escolha não-óbvia.

## Findings da revisão
Preenchido pelo `/task-review`. Cada rodada adiciona uma seção com data. Marcar `[x]` quando o finding for endereçado (no `/task-start` seguinte).

<!-- exemplo:
### 2026-11-17 15:32
- [ ] **Violação de diretriz** `shared/src/commonMain/kotlin/com/bluemessenger/chat/ChatService.kt:47` — exceção de transporte capturada genericamente sem mapear para `DomainError`. Corrigir.
-->

## Métricas de Execução
Espaço para registrar o tempo de execução e o consumo de tokens gastos em cada ciclo de trabalho da tarefa (implementação e resolução de bugs/findings).

<!-- exemplo:
- **Implementação Inicial (YYYY-MM-DD HH:MM)**:
  - Tempo de execução: `Xm Ys`
  - Tokens consumidos: `~Z tokens`
- **Resolução de Findings / Bugs (YYYY-MM-DD HH:MM)**:
  - Tempo de execução: `Xm Ys`
  - Tokens consumidos: `~Z tokens`
-->

## Histórico
Timeline de transições entre estágios. Preenchido automaticamente pelos comandos.

<!-- exemplo:
- 2026-11-17 14:10 · criada (prototype)
- 2026-11-17 14:22 · iniciada (doing)
- 2026-11-17 15:05 · em revisão (review) — 3 findings
- 2026-11-17 15:40 · retomada (doing) — findings sendo endereçados
- 2026-11-17 16:02 · em revisão (review) — limpa
- 2026-11-17 16:10 · fechada (done)
-->
