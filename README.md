# APK / VR

## QuestCraft no celular (experimental)

A adaptação do **QuestCraft 6.0.0** para experimentar num telefone Android está
em [`questcraft_mobile/`](questcraft_mobile/README.md). O build prepara o APK
para uso com o runtime **PhoneXR** (instalado separadamente), que fornece o
rastreamento de cabeça e de mãos. Ainda não foi testado em um dispositivo real,
e o funcionamento dos gestos no jogo não está garantido. O APK do GoFile não
foi baixado neste ambiente; a build automática usa o release oficial de mesmo
nome e confere seu hash.

## Launcher web existente

`src/`, `public/` e `android/` contêm um launcher independente para os jogos
web Gorilla Rush e CraftVR. Eles **não são QuestCraft** e não modificam o APK
nativo solicitado. Para abrir essa interface, instale as dependências com
`npm ci` e rode `npm run dev -- --host 0.0.0.0`.
