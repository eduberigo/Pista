# Pista

App de DJ para Android (Samsung S26 Ultra), com separação de faixas feita no próprio celular.

## O que faz
- 2 decks com forma de onda colorida, visão geral e onda vertical com zoom
- BPM, grade de batidas com downbeat (o "1" do compasso), começo da música e tom (Camelot) detectados automaticamente
- SYNC de andamento e de fase (batida ou compasso), keylock (andamento sem mudar o tom), mudança de tom e "casar tom"
- Separação em 6 faixas (voz, bateria, baixo, guitarra, piano, outros) com HT-Demucs 6s rodando no aparelho
- Botões para cortar voz, bateria ou toda a harmonia (baixo + guitarra + piano + outros)
- EQ de 3 bandas com corte total, filtro, ganho, 8 hot cues, loops com quantização, salto em batidas
- Saída estéreo, dividida (cabo Y: mix num lado, fone no outro) ou interface USB de 4 canais
- Biblioteca com busca, ordenação por BPM/tom e filtro de músicas compatíveis com o outro deck

## Instalar
1. Abra **Releases** neste repositório pelo navegador do celular.
2. Baixe o `Pista-0.1.N.apk` mais recente e toque nele.
3. Na primeira vez, permita "Instalar apps desconhecidos" para o navegador.
4. Ao abrir o app, conceda "Acesso a todos os arquivos".
5. Em Ajustes (⚙), escolha a pasta das músicas e toque em **Processar biblioteca**.

Os dados ficam em `/Pista` no armazenamento interno (análises, faixas separadas e o modelo de separação).
Cada `push` na branch `main` gera uma nova versão automaticamente (GitHub Actions).

Créditos: separação com HT-Demucs (Meta AI, licença MIT), exportado em ONNX por StemSplit.
