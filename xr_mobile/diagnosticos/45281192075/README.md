# Teste final da cópia independente — 45281192075

- [Execução no emulador Android 15](https://github.com/stumbledayoh-max/apk/actions/runs/45281192075): concluída com sucesso usando a fixture própria `com.xrpocket.probe`.
- [Build do criador 45277972250](https://github.com/stumbledayoh-max/apk/actions/runs/45277972250), SHA-256 de `BigXR-debug.apk`: `6ed2058f06ba46f4a2d728a6fe2838a806a2b57830cc64b332a44b48fa09ae5e`.
- A cópia da fixture com nome, foto e OBB foi salva, verificada e instalada; o criador foi desinstalado. Na cópia autônoma, o modelo e a câmera iniciaram, o OBB foi extraído, três ciclos OpenXR terminaram e entradas sintéticas e mudança do giroscópio virtual chegaram à sessão sem renderização. A câmera acrescentada ao manifesto não se tornou requisito obrigatório da fixture.
- `signature.txt` e `badging.txt` referem-se ao **APK de fixture gerado**, não ao APK do criador. A linha `BROKER sem runtime` é a consulta antiga da fixture ao broker público; a cópia usa o broker privado e as chamadas OpenXR registradas em `openxr-resumo.txt` passaram.

Não foi testado jogo Meta, renderização de gameplay, gestos de uma pessoa ao vivo nem funcionamento em um celular físico. Uma execução posterior pode usar outra chave e ter outro hash.
