# BigXR — criador Android experimental

O **BigXR** é um aplicativo Android nativo para tentar criar uma cópia de um jogo com Monado e uma ponte de mãos integrados. Você precisa fornecer o **APK original** e, se houver, o OBB do jogo; nome e foto não contêm nem baixam o jogo. Use apenas arquivos que você tenha direito de modificar e usar.

A cópia produzida é **um único APK**, com o jogo e os componentes BigXR dentro dela. Ela não precisa do aplicativo BigXR nem de PhoneXR instalados separadamente para iniciar. O modo antigo, que instala o jogo separado, continua disponível como alternativa.

**Isto não é um emulador universal de Quest.** O criador aceita somente alguns APKs Android ARM64 únicos cujo loader OpenXR tenha o formato reconhecido. Muitos jogos Meta/Quest dependem de SDKs, serviços, permissões, hardware ou licenças proprietários e podem não instalar ou não funcionar. Criar, assinar ou abrir um APK não prova que ele renderize ou aceite controles.

## Build de teste

A [build testada](https://github.com/stumbledayoh-max/apk/actions/runs/45277972250) contém o artefato `BigXR-experimental` com `BigXR-debug.apk`. O GitHub pode exigir login e os artefatos da CI expiram. SHA-256 do APK: `6ed2058f06ba46f4a2d728a6fe2838a806a2b57830cc64b332a44b48fa09ae5e`.

## Como usar

1. Instale e abra o **BigXR** em um Android 10+ ARM64.
2. Em **Selecionar APK**, escolha o APK original. Se existir, selecione também um OBB `main.<versão>.<pacote>.obb` ou `patch.<versão>.<pacote>.obb`. OBBs são opcionais; por enquanto, é possível incluir apenas um deles.
3. Digite o nome e, se quiser, escolha uma foto para o ícone. Toque em **Criar APK com BigXR** e confirme os avisos. O processamento é local e exige espaço livre; o APK original é limitado a 1,6 GB e, com OBB, a 1,7 GB antes da integração.
4. Toque em **Salvar APK criado** para escolher onde guardá-lo. O arquivo temporário no BigXR pode desaparecer ao limpar o cache. Em **Instalar APK criado**, autorize instalações para o BigXR se o Android pedir e confirme na tela do sistema.
5. Abra o ícone do jogo criado. Na tela BigXR integrada, escolha abrir com ou sem câmera. Se um OBB foi incluído, o app tenta copiá-lo para a pasta do próprio jogo antes de abrir. Por segurança, não substitui um OBB diferente que já exista; alguns aparelhos podem bloquear a cópia.

A cópia conserva o nome de pacote original, mas recebe uma **nova assinatura local**, armazenada no Android Keystore do BigXR. O Android não permitirá atualizar diretamente a versão oficial com outra assinatura. Desinstalar a versão original pode apagar seus dados; faça backup antes. Desinstalar ou limpar os dados do BigXR pode remover a chave local, impedindo futuras atualizações de cópias assinadas anteriormente. Licenças, compras e verificações de integridade do jogo podem falhar; o BigXR não as remove nem garante que continuem funcionando.

No [teste em emulador Android 15](https://github.com/stumbledayoh-max/apk/actions/runs/45281192075), uma fixture própria foi personalizada, salva e instalada. Depois de desinstalar o criador, a cópia iniciou a câmera e o modelo, extraiu o OBB e concluiu ciclos OpenXR com entradas sintéticas. **Isso não é teste de gameplay, gestos ao vivo nem de um celular físico.**

## Limitações e segurança

- APKs divididos (`.apks`, `.apkm`, `.xapk` ou splits obrigatórios), jogos sem loader OpenXR ARM64 reconhecido e conflitos de classes ou bibliotecas são recusados. O criador não converte gráficos, arquitetura ou APIs proprietárias do jogo. A seleção de uma imagem e um nome não corrige incompatibilidades.
- A câmera traseira é usada para estimar mãos; a ponte envia estados aproximados de controles ao Monado por UDP local (`127.0.0.1:42424`). Não é rastreamento nativo completo de dedos nem 6DoF calibrado, e outros apps locais podem enviar pacotes nessa porta. Quando visível, a notificação permite parar a câmera; o serviço tenta parar ao sair do jogo. Verifique o indicador de câmera e as configurações do Android.
- O runtime tenta usar os sensores Android, incluindo o giroscópio quando disponível. A detecção da câmera, entradas sintéticas ou movimento de sensor em teste não garantem gestos reais, orientação correta ou gameplay em celular físico.
- As bibliotecas Monado fixadas têm segmentos ELF alinhados a 4 KB; o alinhamento do ZIP não comprova funcionamento em aparelhos que exigem páginas de memória de 16 KB.
- O BigXR distribuído pela CI é uma **build de depuração**; a chave da CI muda entre execuções. O runtime binário PhoneXR/Monado está fixado, mas seus direitos de redistribuição não foram confirmados. Confirme-os antes de redistribuir esta build ou APKs criados com ela.
- Os detalhes técnicos, limites e instruções de compilação estão em [`xr_mobile/README.md`](xr_mobile/README.md). O experimento anterior específico de QuestCraft permanece em [`questcraft_mobile/`](questcraft_mobile/README.md).
