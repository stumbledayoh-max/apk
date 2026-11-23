# QuestCraft Mobile — port experimental

Este repositório é dedicado à adaptação **não oficial e experimental** do QuestCraft 6.0.0 para Android. O gerador em [`questcraft_mobile/`](questcraft_mobile/README.md) incorpora runtime OpenXR, ponte de câmera e modelo de mãos **no mesmo APK**. Não é necessário instalar o aplicativo PhoneXR separadamente. A integração não foi validada em celular físico: pode não abrir e não há prova de gameplay, mãos ou giroscópio funcionando.

O APK do GoFile não pôde ser baixado neste ambiente. A build automática usa o release oficial de mesmo nome e confere seu hash. Os jogos web e o antigo launcher Android que não pertenciam ao QuestCraft foram removidos.

## Baixar e testar

1. Na [página da build](https://github.com/stumbledayoh-max/apk/actions/workflows/questcraft-mobile.yml?query=branch%3Aarena%2Fe3a80795-apk), procure uma execução bem-sucedida com o artefato `QuestCraft-Mobile-unico-experimental` (o GitHub normalmente pede login). Não use o artefato antigo `QuestCraft-Mobile-debug-experimental`, que dependia do PhoneXR instalado à parte.
2. Extraia o ZIP e instale `QuestCraft-Mobile-debug.apk` em Android 10+ ARM64. Abra-o pelo ícone e permita a câmera se quiser tentar os gestos.
3. Leia o [guia completo](questcraft_mobile/README.md) sobre assinatura, backup e limitações. **Faça backup antes de desinstalar outra versão**, pois isso pode apagar dados. O jogo completo original exige uma conta Microsoft com Minecraft: Java Edition; o lançamento também menciona uma demo, não verificada no port.

O APK é experimental e pode falhar. As permissões de redistribuição de componentes do QuestCraft e PhoneXR não foram estabelecidas; consulte os titulares antes de compartilhar builds incorporando-os.

## Página informativa

A página em `src/` não é o jogo e não gera APK no navegador; reúne o link da build, instruções e estado das imagens. Para executá-la:

```sh
npm ci
npm run dev -- --host 0.0.0.0
```

A imagem de divulgação em `public/images/questcraft-oficial.jpg` vem do [QuestCraft oficial](https://github.com/QuestCraftPlusPlus/QuestCraft/blob/6.0.0/QCSimple3.jpg). Ela é referência, **não uma captura do port**.

`public/images/questcraft-port-emulador.jpg` mostra o aviso real de hardware em um emulador durante uma **versão anterior**, que voltou à tela inicial após Continue. Ela não mostra gameplay nem comprova o APK único ou um celular físico.

## Testes

```sh
npm ci && npm run build
python3 -m unittest discover -s questcraft_mobile/tests -v
# Com Chromium instalado e o servidor Vite rodando:
npm run test:site
```
