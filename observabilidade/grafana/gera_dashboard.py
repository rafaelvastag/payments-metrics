import json, sys

DS = {"type": "prometheus", "uid": "prometheus"}
P = "pagamento_evento_processamento_seconds"
F = 'env=~"$env", arranjo=~"$arranjo", status_evento=~"$status_evento"'
FA = 'env=~"$env", arranjo=~"$arranjo"'
FE = 'env=~"$env"'
RI = "[$__rate_interval]"

panels = []
_id = [0]
y = [0]


def nid():
    _id[0] += 1
    return _id[0]


def row(title):
    panels.append({"type": "row", "title": title, "id": nid(), "collapsed": False,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y[0]}, "panels": []})
    y[0] += 1


cursor = {"x": 0, "h": 0}


def place(w, h):
    if cursor["x"] + w > 24:
        y[0] += cursor["h"]
        cursor["x"] = 0
        cursor["h"] = 0
    pos = {"h": h, "w": w, "x": cursor["x"], "y": y[0]}
    cursor["x"] += w
    cursor["h"] = max(cursor["h"], h)
    return pos


def flush():
    y[0] += cursor["h"]
    cursor["x"] = 0
    cursor["h"] = 0


def tgt(expr, legend="", ref="A", **kw):
    t = {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref, "range": True}
    t.update(kw)
    return t


def ts(title, targets, w=12, h=8, unit="short", stack=False, desc="", overrides=None, draw="line"):
    p = {
        "type": "timeseries", "title": title, "id": nid(), "datasource": DS, "description": desc,
        "gridPos": place(w, h), "targets": targets,
        "fieldConfig": {"defaults": {"unit": unit, "custom": {
            "drawStyle": draw, "lineWidth": 1, "fillOpacity": 25 if stack else 8,
            "stacking": {"mode": "normal" if stack else "none", "group": "A"},
            "showPoints": "never", "spanNulls": True}}, "overrides": overrides or []},
        "options": {"legend": {"displayMode": "table", "placement": "right", "calcs": ["mean", "max", "lastNotNull"]},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
    }
    panels.append(p)


def stat(title, expr, unit="short", w=4, h=4, thresholds=None, desc="", decimals=None):
    p = {
        "type": "stat", "title": title, "id": nid(), "datasource": DS, "description": desc,
        "gridPos": place(w, h), "targets": [tgt(expr, instant=True, range=False)],
        "fieldConfig": {"defaults": {"unit": unit, "color": {"mode": "thresholds"},
                                     "thresholds": {"mode": "absolute", "steps": thresholds or [{"color": "blue", "value": None}]}},
                        "overrides": []},
        "options": {"reduceOptions": {"calcs": ["lastNotNull"]}, "colorMode": "background", "graphMode": "area",
                    "textMode": "value"},
    }
    if decimals is not None:
        p["fieldConfig"]["defaults"]["decimals"] = decimals
    panels.append(p)


def table(title, expr, labels, w=12, h=9, desc=""):
    organize = {"excludeByName": {"Time": True}, "renameByName": {"Value": "Total no período"}}
    p = {
        "type": "table", "title": title, "id": nid(), "datasource": DS, "description": desc,
        "gridPos": place(w, h),
        "targets": [tgt(expr, format="table", instant=True, range=False)],
        "transformations": [
            {"id": "organize", "options": organize},
            {"id": "sortBy", "options": {"sort": [{"field": "Total no período", "desc": True}]}},
        ],
        "fieldConfig": {"defaults": {"decimals": 0, "custom": {"align": "auto"}}, "overrides": [
            {"matcher": {"id": "byName", "options": "Total no período"},
             "properties": [{"id": "custom.cellOptions", "value": {"type": "gauge", "mode": "lcd"}},
                            {"id": "color", "value": {"mode": "continuous-YlRd"}}]}]},
        "options": {"showHeader": True, "cellHeight": "sm"},
    }
    panels.append(p)


def color(name, c):
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": c}}]}


def q(quant, by, metric, filt):
    b = f"le, {by}" if by else "le"
    return f"histogram_quantile({quant}, sum by ({b}) (rate({metric}_bucket{{{filt}}}{RI})))"


# ================= Visão geral =================
row("Visão geral")
total = f"sum(rate({P}_count{{{F}}}{RI}))"
stat("Eventos/s", total, "reqps", desc="Vazão total de eventos processados (Timer._count).")
stat("% sucesso", f'sum(rate({P}_count{{{F}, outcome="success"}}{RI})) / {total}', "percentunit",
     thresholds=[{"color": "red", "value": None}, {"color": "orange", "value": 0.9}, {"color": "green", "value": 0.97}], decimals=2)
stat("% rejeitado (regra)", f'sum(rate({P}_count{{{F}, outcome="rejected"}}{RI})) / {total}', "percentunit",
     thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 0.03}, {"color": "red", "value": 0.1}], decimals=2,
     desc="Transição inválida e cenários não mapeados: recusa de negócio, não é falha técnica.")
stat("% erro técnico", f'sum(rate({P}_count{{{F}, outcome="error"}}{RI})) / {total}', "percentunit",
     thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 0.01}, {"color": "red", "value": 0.05}], decimals=2)
stat("p95 processamento", q(0.95, "", P, F), "s",
     thresholds=[{"color": "green", "value": None}, {"color": "orange", "value": 0.25}, {"color": "red", "value": 0.5}])
stat("% dentro do SLO (≤ 250ms)",
     f'sum(rate({P}_bucket{{{F}, le="0.25"}}{RI})) / {total}', "percentunit",
     thresholds=[{"color": "red", "value": None}, {"color": "orange", "value": 0.95}, {"color": "green", "value": 0.99}], decimals=2,
     desc="Fração de eventos processados em até 250ms — leitura direta do SLO bucket, sem estimativa de percentil.")
flush()

# ================= Vazão por tag =================
row("Vazão e resultado por tag")
ts("Eventos/s por arranjo", [tgt(f"sum by (arranjo) (rate({P}_count{{{F}}}{RI}))", "{{arranjo}}")], unit="reqps", stack=True)
ts("Eventos/s por status do evento", [tgt(f"sum by (status_evento) (rate({P}_count{{{F}}}{RI}))", "{{status_evento}}")], unit="reqps", stack=True)
ts("Resultado (outcome)", [tgt(f"sum by (outcome) (rate({P}_count{{{F}}}{RI}))", "{{outcome}}")], unit="reqps", stack=True,
   overrides=[color("success", "green"), color("rejected", "yellow"), color("error", "red")])
ts("Não-sucesso por tipo de erro", [tgt(f'sum by (error_type) (rate({P}_count{{{F}, outcome!="success"}}{RI}))', "{{error_type}}")],
   unit="reqps", stack=True, desc="error.type categoriza a falha: db, servicebus, timeout, transicao_invalida, nao_mapeado...")
ts("Taxa de erro técnico por arranjo", [tgt(
    f'sum by (arranjo) (rate({P}_count{{{F}, outcome="error"}}{RI})) / sum by (arranjo) (rate({P}_count{{{F}}}{RI}))', "{{arranjo}}")],
   unit="percentunit")
table("Volume no período: arranjo × status × resultado",
      f"sum by (arranjo, status_evento, outcome) (increase({P}_count{{{F}}}[$__range]))",
      ["arranjo", "status_evento", "outcome"])
flush()

# ================= Latência =================
row("Latência do processamento")
ts("Processamento p50 / p95 / p99", [
    tgt(q(0.50, "", P, F), "p50", "A"),
    tgt(q(0.95, "", P, F), "p95", "B"),
    tgt(q(0.99, "", P, F), "p99", "C"),
    tgt(f"max(max_over_time({P}_max{{{F}}}{RI}))", "max (eixo direito)", "D")], unit="s",
   overrides=[{"matcher": {"id": "byName", "options": "max (eixo direito)"},
               "properties": [{"id": "custom.axisPlacement", "value": "right"},
                              {"id": "custom.lineStyle", "value": {"fill": "dash", "dash": [6, 4]}}]}],
   desc="O max vem do Timer._max (pior caso da janela) e fica no eixo direito para não achatar os percentis.")
ts("p95 por arranjo", [tgt(q(0.95, "arranjo", P, F), "{{arranjo}}")], unit="s")
ts("Tempo médio por status do evento", [tgt(
    f"sum by (status_evento) (rate({P}_sum{{{F}}}{RI})) / sum by (status_evento) (rate({P}_count{{{F}}}{RI}))", "{{status_evento}}")], unit="s")
ts("Espera na fila p95 por arranjo (enqueuedTime → início)", [tgt(
    q(0.95, "arranjo", "pagamento_evento_espera_fila_seconds", FA), "{{arranjo}}")], unit="s",
   desc="Backlog: tempo que a mensagem ficou na fila antes de ser processada. Em POC, aumente com PUT /simulador?atrasoFilaMs=3000.")
panels.append({
    "type": "heatmap", "title": "Distribuição do tempo de processamento (SLO buckets)", "id": nid(), "datasource": DS,
    "gridPos": place(24, 8),
    "targets": [tgt(f"sum by (le) (increase({P}_bucket{{{F}}}{RI}))", "{{le}}", format="heatmap")],
    "options": {"calculate": False, "yAxis": {"unit": "s"}, "cellGap": 1,
                "color": {"mode": "scheme", "scheme": "Oranges", "steps": 64}, "tooltip": {"show": True, "yHistogram": True}},
})
flush()

# ================= Banco =================
row("Banco de dados")
D = "pagamento_db_operacao_seconds"
ts("p95 por operação de banco", [tgt(q(0.95, "operacao", D, FE), "{{operacao}}")], w=8, unit="s")
ts("Operações/s por operação e resultado", [tgt(f"sum by (operacao, outcome) (rate({D}_count{{{FE}}}{RI}))", "{{operacao}} · {{outcome}}")],
   w=8, unit="ops")
ts("Erros de banco por tipo", [tgt(f'sum by (operacao, error_type) (rate({D}_count{{{FE}, outcome="error"}}{RI}))', "{{operacao}} · {{error_type}}")],
   w=8, unit="ops", stack=True, draw="bars")
flush()

# ================= Service Bus =================
row("Service Bus — filas de saída")
S = "pagamento_servicebus_envio_seconds"
ts("Envios/s por fila de destino", [tgt(f"sum by (destino) (rate({S}_count{{{FA}}}{RI}))", "{{destino}}")], w=8, unit="reqps", stack=True)
ts("Falhas de envio por destino e tipo", [tgt(f'sum by (destino, error_type) (rate({S}_count{{{FA}, outcome="error"}}{RI}))', "{{destino}} · {{error_type}}")],
   w=8, unit="reqps", stack=True, draw="bars")
ts("p95 de envio por destino", [tgt(q(0.95, "destino", S, FA), "{{destino}}")], w=8, unit="s")
flush()

# ================= Máquina de estado =================
row("Máquina de estado, cenários não mapeados e reentregas")
T = "pagamento_transicao_invalida_total"
ts("Transições inválidas/min (estado salvo → status recebido)",
   [tgt(f"sum by (estado_atual, status_evento) (rate({T}{{{F}}}{RI})) * 60", "{{estado_atual}} → {{status_evento}}")],
   unit="short", stack=True, desc="Cada ponto gerou uma ocorrência de falha gravada no banco.")
table("Matriz de transições inválidas no período",
      f"sum by (arranjo, estado_atual, status_evento) (increase({T}{{{F}}}[$__range]))",
      ["arranjo", "estado_atual", "status_evento"])
N = "pagamento_evento_nao_mapeado_total"
ts("Não mapeados/min por motivo", [tgt(f"sum by (motivo) (rate({N}{{{FA}}}{RI})) * 60", "{{motivo}}")],
   w=12, unit="short", stack=True)
ts("Reentregas/s por arranjo (deliveryCount > 1)", [tgt(f"sum by (arranjo) (rate(pagamento_evento_reentrega_total{{{FA}}}{RI}))", "{{arranjo}}")],
   w=12, unit="reqps", stack=True)
flush()


def var(name, label, query, include_all=True):
    return {"name": name, "label": label, "type": "query", "datasource": DS,
            "query": {"query": query, "refId": name}, "definition": query,
            "multi": True, "includeAll": include_all, "allValue": ".*",
            "current": {"selected": True, "text": ["All"], "value": ["$__all"]},
            "refresh": 2, "sort": 1}


dash = {
    "uid": "pagamentos-fluxo",
    "title": "Pagamentos — fluxo de eventos",
    "tags": ["pagamentos", "micrometer"],
    "timezone": "browser",
    "schemaVersion": 41,
    "version": 1,
    "refresh": "10s",
    "time": {"from": "now-15m", "to": "now"},
    "templating": {"list": [
        var("env", "Ambiente", f"label_values({P}_count, env)"),
        var("arranjo", "Arranjo", f'label_values({P}_count{{env=~"$env"}}, arranjo)'),
        var("status_evento", "Status do evento", f'label_values({P}_count{{env=~"$env", arranjo=~"$arranjo"}}, status_evento)'),
    ]},
    "annotations": {"list": []},
    "panels": panels,
}

json.dump(dash, open(sys.argv[1], "w"), ensure_ascii=False, indent=2)
print(len(panels), "painéis")
