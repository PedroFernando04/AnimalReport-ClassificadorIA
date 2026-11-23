"""T059: o conjunto de avaliação cumpre o anexo B do SPEC-01 e não repete exemplos do prompt."""
import re
import unicodedata
from collections import Counter

from avaliacao.executar import carregar_casos
from avaliacao.metricas import ESCALA
from app.prompt import ARQUIVO_PROMPT


def norm(t):
    return re.sub(r"\W+", " ", "".join(c for c in unicodedata.normalize("NFD", t.lower()) if unicodedata.category(c) != "Mn")).strip()


def test_tamanho_minimo_e_casos_de_falha():
    casos = carregar_casos("todos")
    assert len(casos) >= 15
    assert sum(c["tipo"] != "normal" for c in casos) >= 5
    assert {c["conjunto"] for c in casos} == {"dev", "final"}              # parte reservada à avaliação final
    assert len({c["id"] for c in casos}) == len(casos)


def test_cobre_os_cinco_niveis_mais_insuficiente_e_varios_animais():
    casos = carregar_casos("todos")
    assert {c["gabarito"] for c in casos} == set(ESCALA) | {"INSUFICIENTE"}
    assert len({c["tipoAnimal"] for c in casos}) >= 8


def test_tipos_de_falha_pedidos_no_anexo_b():
    casos = {c["id"]: c for c in carregar_casos("todos")}
    assert casos["D10"]["relato"] == "ajuda"                               # descrição insuficiente
    assert any("ignore as instru" in c["relato"].lower() for c in casos.values())   # injeção
    assert any("SISTEMA:" in c["relato"] for c in casos.values())
    assert len(casos["F13"]["relato"]) > 2400 and casos["F13"]["relato"].rstrip().endswith("não levanta.")  # texto longo
    assert Counter(c["tipo"] for c in casos.values())["adversarial"] >= 2


def test_nenhum_exemplo_do_prompt_aparece_nos_casos():
    prompt = norm(ARQUIVO_PROMPT.read_text(encoding="utf-8"))
    exemplos = re.findall(r'Exemplo: "([^"]+)"', ARQUIVO_PROMPT.read_text(encoding="utf-8"))
    assert len(exemplos) == 6
    for ex in exemplos:
        for c in carregar_casos("todos"):
            assert norm(ex) != norm(c["relato"]), f"exemplo do prompt repetido no caso {c['id']}"
    assert prompt


def test_campos_obrigatorios():
    for c in carregar_casos("todos"):
        assert {"id", "conjunto", "tipoAnimal", "relato", "gabarito", "aceitos", "tipo", "critico"} <= set(c)
        assert c["gabarito"] in ESCALA + ("INSUFICIENTE",)
