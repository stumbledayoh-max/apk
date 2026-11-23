# QuestCraft Mobile — port experimental

Este repositório é dedicado à adaptação experimental do **QuestCraft 6.0.0**
para Android. O gerador em [`questcraft_mobile/`](questcraft_mobile/README.md)
prepara o APK para uso com o **PhoneXR**, instalado separadamente. O PhoneXR
fornece a câmera e o rastreamento de cabeça; o funcionamento dos gestos no
QuestCraft ainda não foi confirmado em um aparelho físico.

O APK do GoFile não pôde ser baixado neste ambiente. A build automática usa o
release oficial de mesmo nome e confere seu hash. Os jogos web e o antigo
launcher Android que não pertenciam ao QuestCraft foram removidos.

## Baixar e testar

1. Abra a [última execução da build](https://github.com/stumbledayoh-max/apk/actions/workflows/questcraft-mobile.yml?query=branch%3Aarena%2Fe3a80795-apk), entre no GitHub e baixe o artefato `QuestCraft-Mobile-debug-experimental`.
2. Instale o [PhoneXR 2.0.1 completo](https://github.com/samrat1games/phonexr/releases/tag/v2.0.1), permita a câmera e instale seu runtime.
3. Extraia e instale `QuestCraft-Mobile-debug.apk` e inicie-o pela biblioteca do PhoneXR.

Leia o [guia completo](questcraft_mobile/README.md), incluindo os cuidados com
assinatura, backup, requisitos e limitações. A build é experimental e pode não
funcionar no seu telefone. Ela requer Minecraft: Java Edition em uma conta
Microsoft.

## Página informativa

A página em `src/` não é o jogo e não gera APK no navegador; ela reúne o link da
build, instruções e estado das imagens. Para executá-la:

```sh
npm ci
npm run dev -- --host 0.0.0.0
```

A imagem de divulgação em `public/images/questcraft-oficial.jpg` vem do
[repositório oficial do QuestCraft](https://github.com/QuestCraftPlusPlus/QuestCraft/blob/6.0.0/QCSimple3.jpg).
Ela é apresentada como referência e **não** como uma captura do port.

A imagem `public/images/questcraft-port-emulador.jpg` mostra o aviso real de
hardware durante a inicialização direta do APK no emulador Android ([execução da captura](https://github.com/stumbledayoh-max/apk/actions/runs/45086657341)).
Ao selecionar “Continue” em uma [tentativa posterior](https://github.com/stumbledayoh-max/apk/actions/runs/45087023867), o teste voltou à tela inicial do Android. Isso não testa o lançamento pelo PhoneXR. A imagem não mostra gameplay e não substitui um teste em celular.

## Testes

```sh
npm ci && npm run build
python3 -m unittest discover -s questcraft_mobile/tests -v

# Em outro terminal, rode o servidor; para o teste visual, instale o Chromium do Playwright:
npx playwright install chromium
npm run dev -- --host 0.0.0.0
# Depois execute:
npm run test:site
```
