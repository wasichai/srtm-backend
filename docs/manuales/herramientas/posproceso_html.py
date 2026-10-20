"""Retoques del HTML de asciidoctor que la hoja de estilos no alcanza. Se corre después de make html.

- el título «Manual: subtítulo» se parte en título y subtítulo, como en la portada del PDF;
- la edición pierde la coma con que la cierra asciidoctor («edición 1.0,»).
"""
import pathlib
import re
import sys

raiz = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "salida/html")
for html in raiz.rglob("*.html"):
    texto = html.read_text(encoding="utf-8")
    nuevo = re.sub(
        r'<h1>([^<]+?): ([^<]+)</h1>',
        r'<h1>\1<small class="subtitle">\2</small></h1>',
        texto,
        count=1,
    )
    nuevo = re.sub(r'(<span id="revnumber">[^<]*?),</span>', r"\1</span>", nuevo)
    if nuevo != texto:
        html.write_text(nuevo, encoding="utf-8")
