# LegendaAI

Aplicativo Android para selecionar um vídeo, extrair sua faixa de áudio, transcrever fala em português com a API de transcrição da OpenAI e exportar os tempos e textos em arquivo SRT.

## Recursos desta versão
- Seleção de vídeo pelo seletor seguro do Android.
- Extração local da faixa de áudio.
- Transcrição em português usando `whisper-1`.
- Prévia dos trechos com marcação de tempo.
- Exportação de legenda `.srt`.

## Requisito da API
É necessário informar uma chave da API OpenAI no próprio app. A chave não está embutida no código nem armazenada no repositório. A chamada de transcrição pode gerar cobrança na conta da API. O áudio é enviado à API para processamento.

## Limitação conhecida
Esta primeira versão gera um arquivo SRT separado. Ainda não renderiza/queima as legendas dentro do vídeo MP4 nem inclui os estilos animados de palavras. Não confundir a exportação SRT com um vídeo final legendado.

## Compilar
O workflow em `.github/workflows/android.yml` compila automaticamente um APK de depuração a cada push na branch `main`. O arquivo fica nos artefatos da execução do GitHub Actions por 30 dias.
