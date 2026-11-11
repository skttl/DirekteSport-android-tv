# Projektregler

## HTTP-integration med DirekteSport

Ved ændringer i HTTP-integrationen med DirekteSport skal både appen og HTTP-integrationstestene opdateres i samme ændring. Det gælder blandt andet endpoints, parametre, JSON-format, videokonfiguration og HLS-kald. Hold appens klienter, `tests/direktesport_http.py` og de tilhørende tests i `tests/test_direktesport_http.py` i overensstemmelse.
