# Teste da cópia independente — 45276416280

- [Execução no emulador Android 15](https://github.com/stumbledayoh-max/apk/actions/runs/45276416280): concluída com sucesso usando a fixture própria `com.xrpocket.probe`.
- [Build do criador 45268742815](https://github.com/stumbledayoh-max/apk/actions/runs/45268742815), SHA-256 de `BigXR-debug.apk`: `8c4e5c8ae65c3820445eed83e5fcedaa39ce939a3d67448a0365aef27ba30c20`.
- A cópia da fixture com nome, foto e OBB foi salva, verificada e instalada; o criador foi desinstalado. Na cópia autônoma, o modelo e a câmera iniciaram, o OBB foi extraído, três ciclos OpenXR terminaram e entradas sintéticas e mudança do giroscópio virtual chegaram à sessão sem renderização.
- `signature.txt` e `badging.txt` referem-se ao **APK de fixture gerado**, não ao APK do criador. A linha `BROKER sem runtime` é a consulta antiga da fixture ao broker público; a cópia usa o broker privado e as chamadas OpenXR registradas em `openxr-resumo.txt` passaram.

Não foi testado jogo Meta, renderização de gameplay, gestos de uma pessoa ao vivo nem funcionamento em um celular físico. Uma execução posterior pode usar outra chave e ter outro hash.
